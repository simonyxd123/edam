package com.example.edam.controller;

import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.CurrentUserId;
import com.example.edam.service.WatermarkExtractClient;
import com.example.edam.service.WatermarkRegistryService;
import lombok.Data;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 水印与溯源 Controller
 * 对应 openapi.yaml tag: watermarks
 */
@Slf4j
@RestController
@RequestMapping("/watermarks")
@RequiredArgsConstructor
public class WatermarkController {

    private final WatermarkExtractClient extractClient;
    private final DocResourceRepository docResourceRepository;
    private final SysUserRepository sysUserRepository;
    private final WatermarkRegistryService registryService;

    /**
     * 单次提取的体积上限 —— 与 Worker 侧的限制一致（50MB）。
     *
     * <p><b>为什么必须有（2026-11-14 代码审计第五轮）</b>：
     * 全局 multipart 上限是 8GB（视频需要），而本端点此前直接
     * {@code file.getBytes()} —— <b>把整个文件读进 JVM 堆</b>。
     * 而 {@code watermark:read} 在<b>普通员工</b>的白名单里
     * （见 {@code EmployeePermissions.READ_ONLY}）⇒
     * 任何一个普通员工都能上传一个 1GB 的文件把后端 OOM 掉，
     * Pod 被杀 ⇒ 全站不可用。Worker 那边有 50MB 限制，
     * 但后端是<b>先读进堆、再转发</b>的，Worker 的限制根本来不及生效。
     */
    public static final long MAX_EXTRACT_BYTES = 50L * 1024 * 1024;

    /**
     * 支持提取的文件类型 —— 与 Worker 的 {@code internal.py} 保持一致。
     *
     * <p>Office 的水印是隐写，提取算法未实现（Worker 会明确报 400）。
     * 在**后端**就挡住，一是省一次无谓的往返，二是避免把
     * Worker 的 400 变成后端的 503（客户端错误被当成服务端故障）。
     */
    private static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of("image", "pdf");

    /**
     * 提取水印/指纹。
     *
     * <p><b>此前这里返回的是一个假的"没查到泄露"</b>：
     * {@code confidence=0.0} + {@code matched_users=[]}，
     * 而且**什么都没扫过**。那比不提供这个端点更危险 ——
     * 运维看到"没有匹配用户"会得出"这个文件是干净的"这个错误结论。
     *
     * <p>现在真的调 Worker 提取（{@link WatermarkExtractClient}）：
     * <ul>
     *   <li>提取到水印 → 按水印文本里的 {@code {上传者ID}|{文档ID}|{时间戳}}
     *       反查文档与上传者，返回真实的匹配用户</li>
     *   <li>提取不到水印 → {@code extracted=false} + 空 matched_users
     *       （"这个文件里没有水印"是**有效结论**）</li>
     *   <li>提取过程失败（Worker 不可达 / 算法缺失 / 文件损坏）→
     *       <b>503 + 明确原因</b>，绝不伪装成"没查到"</li>
     * </ul>
     */
    @PostMapping("/extract")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('watermark:read')")
    @AuditOp(op = AuditOpType.CONFIG_CHANGE, resourceType = "watermark",
        detail = "action=extract, type=#type")
    public ResponseEntity<Map<String, Object>> extract(
        @RequestParam("file") MultipartFile file,
        @RequestParam("type") String type,
        @CurrentUserId Long operatorId
    ) {
        log.info("watermark_extract_request, type={}, size={}, operator_id={}",
            type, file.getSize(), operatorId);

        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        // 类型白名单 —— 在**读进堆之前**就拒掉不支持的类型
        String normalizedType = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException(
                "暂不支持从 " + type + " 提取水印；当前支持 " + SUPPORTED_TYPES
                + "（Office 的水印是隐写，提取算法未实现）");
        }
        // 体积上限 —— 必须在 getBytes() 之前判。
        // 没有这条，普通员工一个 1GB 的上传就能把后端 OOM 掉。
        if (file.getSize() > MAX_EXTRACT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "文件过大（" + file.getSize() + " 字节），提取上限 "
                    + MAX_EXTRACT_BYTES + " 字节");
        }

        WatermarkExtractClient.ExtractResult result;
        try {
            byte[] content = file.getBytes();
            result = extractClient.extract(content, normalizedType);
        } catch (WatermarkExtractClient.WorkerUnavailableException e) {
            // 提取失败 ≠ 没有水印 —— 必须让调用方知道这是系统故障
            log.error("watermark_extract_failed, operator_id={}, error={}",
                operatorId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "盲水印提取失败（Worker 不可用或算法缺失）：" + e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "读取上传文件失败: " + e.getMessage());
        }

        Map<String, Object> extracted = new HashMap<>();
        List<Map<String, Object>> matchedUsers = new ArrayList<>();

        if (result.extracted() && !result.text().isBlank()) {
            String fingerprint = result.text();
            extracted.put("employee_no", "");
            extracted.put("fingerprint", fingerprint);
            extracted.put("confidence", 1.0);

            matchedUsers.addAll(matchUsers(fingerprint));
            if (!matchedUsers.isEmpty()) {
                Map<String, Object> first = matchedUsers.get(0);
                extracted.put("employee_no", String.valueOf(first.get("employee_no")));
            }
        } else {
            extracted.put("employee_no", "");
            extracted.put("fingerprint", "");
            extracted.put("confidence", 0.0);
        }
        extracted.put("extract_time", java.time.OffsetDateTime.now().toString());

        Map<String, Object> response = new HashMap<>();
        response.put("type", normalizedType);
        response.put("extracted", extracted);
        response.put("matched_users", matchedUsers);

        return ResponseEntity.ok(response);
    }

    /**
     * 按水印文本反查匹配的文档与上传者。
     *
     * <p>水印文本格式（见 {@code worker/src/edam_worker/processors/document.py}）：
     * {@code {uploader_id}|{doc_id}|{timestamp}}。
     *
     * <p><b>一致性校验（2026-11-14 代码审计第五轮）</b>：
     * 指纹里的 {@code uploader_id} 必须与库里该文档的上传者<b>一致</b>，
     * 否则**不给匹配用户**。
     *
     * <p>为什么：指纹是**文件内容的一部分**，任何人都能自己写进去。
     * 此前这里只按 {@code doc_id} 反查 —— 于是任何人都能用开源的
     * blind-watermark 库往一张图里嵌入"{同事ID}|{同事的文档ID}|…}"，
     * 提取时就会得出"**这位同事**外泄了这份文件"的结论。
     * 在一个用于追责的系统里，这是能拿来**冤枉人**的。
     *
     * <p>校验之后仍残留的缺口（要彻底堵住需要另做登记，详见
     * {@code modify/2026-11-14-代码审计第五轮.md} §3.3）：
     * 知道目标 {@code doc_id} 与 {@code uploader_id} 的人仍可伪造出一致的指纹 ——
     * 因为系统里**没有"这份文档当初嵌的是哪串文本"的登记**。
     */
    private List<Map<String, Object>> matchUsers(String fingerprint) {
        List<Map<String, Object>> matched = new ArrayList<>();
        String[] parts = fingerprint.split("\\|");
        if (parts.length < 2) {
            log.warn("watermark_text_unrecognized format={}", fingerprint);
            return matched;
        }
        Long docId = null;
        try {
            docId = Long.valueOf(parts[1].trim());
        } catch (NumberFormatException e) {
            log.warn("watermark_text_doc_id_invalid fingerprint={}", fingerprint);
            return matched;
        }
        // 指纹里的 uploader_id（parts[0]）必须能解析 —— 解析不了说明指纹不是
        // 本系统嵌入的格式（系统嵌入时 uploader_id 一定来自登录用户，不会为空）
        Long watermarkUploaderId = null;
        try {
            watermarkUploaderId = Long.valueOf(parts[0].trim());
        } catch (NumberFormatException e) {
            log.warn("watermark_text_uploader_id_invalid fingerprint={}", fingerprint);
            return matched;
        }

        DocResource doc = docResourceRepository.selectById(docId);
        if (doc == null) {
            log.warn("watermark_doc_not_found doc_id={}", docId);
            return matched;
        }

        // 一致性校验：指纹声称的上传者必须就是这份文档的上传者
        if (doc.getUploaderId() == null || !doc.getUploaderId().equals(watermarkUploaderId)) {
            log.warn("watermark_fingerprint_inconsistent, doc_id={}, fingerprint_uploader_id={}, "
                    + "doc_uploader_id={} —— 指纹与库内记录不符，不给出匹配用户",
                docId, watermarkUploaderId, doc.getUploaderId());
            return matched;
        }

        // 登记比对（2026-11-14 代码审计第六轮）：指纹必须与"当初实际嵌入的那串文本"一致。
        //
        // 上一条一致性校验只能挡住"随手伪造一个指针"；知道目标 doc_id 与
        // uploader_id 的人**仍能伪造出一致的指纹**（第五轮 §3.3 的残留缺口）。
        // 有了登记值就能识破：伪造者写进去的文本与登记值对不上。
        // PDF / Excel 的指纹是写在文档属性里的**明文**，本来就能随手改 ——
        // 这一层正是把那种伪造挡在门外的东西。
        WatermarkRegistryService.Verdict verdict = registryService.verify(docId, fingerprint);
        if (verdict == WatermarkRegistryService.Verdict.MISMATCHED) {
            log.warn("watermark_fingerprint_forged, doc_id={} —— 提取到的指纹与登记值不一致，"
                + "判定为伪造，不给出匹配用户", docId);
            return matched;
        }
        if (verdict == WatermarkRegistryService.Verdict.UNKNOWN) {
            // 本功能上线前处理过的历史文档没有登记记录 —— 照常按老逻辑出结论
            log.info("watermark_registry_no_entry, doc_id={} —— 该文档无指纹登记记录", docId);
        }

        Long uploaderId = doc.getUploaderId();
        SysUser uploader = uploaderId == null ? null : sysUserRepository.selectById(uploaderId);
        if (uploader == null) {
            return matched;
        }

        Map<String, Object> m = new HashMap<>();
        m.put("user_id", uploader.getId());
        m.put("employee_no", uploader.getEmployeeNo());
        m.put("real_name", uploader.getRealName());
        m.put("match_score", 1.0);
        m.put("match_time", java.time.OffsetDateTime.now().toString());
        m.put("doc_id", doc.getId());
        m.put("doc_title", doc.getTitle());
        matched.add(m);
        return matched;
    }

    /**
     * 水印指纹登记查询。
     *
     * <p><b>2026-11-14 代码审计第六轮</b>：此前这里恒返回空列表，
     * 注释写着"缓存表没有写入方"。本轮给 {@code watermark_cache} 接上了写入方
     * （Worker 嵌入时回写实际指纹），于是这个端点终于能返回真实数据 ——
     * 顺带也成为运维核查"这份文档登记的指纹是什么"的入口。
     */
    @GetMapping("/cache")
    @AuditOp(op = AuditOpType.LIST, resourceType = "watermark_cache")
    public List<Map<String, Object>> listCache(
        @RequestParam("resource_id") Long resourceId,
        @RequestParam("resource_type") String resourceType
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (com.example.edam.model.WatermarkCache row
                : registryService.listByResource(resourceId, resourceType)) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", row.getId());
            item.put("resource_id", row.getResourceId());
            item.put("resource_type", row.getResourceType());
            item.put("user_id_hash", row.getUserIdHash());
            item.put("fingerprint", row.getFingerprint());
            item.put("minio_path", row.getMinioPath());
            item.put("created_at", row.getCreatedAt());
            item.put("ttl_sec", row.getTtlSec());
            item.put("hit_count", row.getHitCount());
            items.add(item);
        }
        return items;
    }

    /**
     * 清除水印缓存
     */
    @DeleteMapping("/cache/{cache_id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('watermark:manage')")
    @AuditOp(op = AuditOpType.DELETE, resourceType = "watermark_cache", resourceId = "#cacheId")
    public ResponseEntity<Void> deleteCache(@PathVariable("cache_id") Long cacheId) {
        // 同上：缓存表没有写入方，删除是幂等的空操作（返回 204 是准确的）
        log.info("watermark_cache_invalidated, cache_id={}", cacheId);
        return ResponseEntity.noContent().build();
    }
}