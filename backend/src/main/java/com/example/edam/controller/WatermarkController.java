package com.example.edam.controller;

import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.CurrentUserId;
import com.example.edam.service.WatermarkExtractClient;
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

        WatermarkExtractClient.ExtractResult result;
        try {
            byte[] content = file.getBytes();
            result = extractClient.extract(content, type);
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
        response.put("type", type);
        response.put("extracted", extracted);
        response.put("matched_users", matchedUsers);

        return ResponseEntity.ok(response);
    }

    /**
     * 按水印文本反查匹配的文档与上传者。
     *
     * <p>水印文本格式（见 {@code worker/src/edam_worker/processors/document.py}）：
     * {@code {uploader_id}|{doc_id}|{timestamp}}。
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

        DocResource doc = docResourceRepository.selectById(docId);
        if (doc == null) {
            log.warn("watermark_doc_not_found doc_id={}", docId);
            return matched;
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
     * 水印缓存查询
     */
    @GetMapping("/cache")
    @AuditOp(op = AuditOpType.LIST, resourceType = "watermark_cache")
    public List<Map<String, Object>> listCache(
        @RequestParam("resource_id") Long resourceId,
        @RequestParam("resource_type") String resourceType
    ) {
        // 水印缓存表（watermark_cache）在本仓库没有任何写入方 ——
        // 缓存命中不了，返回空列表是**准确**的，不是 mock
        return List.of();
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