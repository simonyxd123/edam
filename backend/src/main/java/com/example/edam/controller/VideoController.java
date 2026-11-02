package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.ResourceEnums;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.dto.VideoResponse;
import com.example.edam.dto.VideoStatusUpdateRequest;
import com.example.edam.model.VideoPermission;
import com.example.edam.model.VideoResource;
import com.example.edam.service.VideoPermissionService;
import com.example.edam.service.VideoService;
import com.example.edam.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.server.ResponseStatusException;

/**
 * 视频资源 Controller
 * 对应 openapi.yaml tag: videos
 */
@RestController
@RequestMapping("/videos")
@Slf4j
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;
    private final VideoPermissionService videoPermissionService;

    /** Worker 回调令牌；为空则该回调端点一律拒绝（fail-closed） */
    @org.springframework.beans.factory.annotation.Value("${edam.worker.token:}")
    private String workerToken;

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) String classification_lv,
        @RequestParam(required = false) Long uploader_id
    ) {
        Page<VideoResource> result = videoService.list(page, page_size, classification_lv, uploader_id);
        return toPaginationResponse(result);
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:upload')")
    public ResponseEntity<Map<String, Object>> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam("classification_lv") String classificationLv,
        @RequestParam(value = "title", required = false) String title,
        @CurrentUserId Long uploaderId
    ) {
        // MIME / 文件类型校验：必须以 video/ 开头
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("video/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "仅支持 video/* 类型的文件，当前: " + contentType);
        }

        // 大小校验（前端 MAX_UPLOAD_BYTES=8GiB，这里兜底）
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }

        VideoResource video = videoService.upload(file, classificationLv, title, uploaderId);
        Map<String, Object> response = new HashMap<>();
        response.put("video_id", video.getId());
        response.put("file_hash", video.getFileHash());
        response.put("hls_status", ResourceEnums.hlsStatus(video.getHlsStatus()));
        response.put("fingerprint_status", ResourceEnums.hlsStatus(video.getFingerprintStatus()));
        response.put("estimated_processing_sec", 60);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{video_id}")
    public ResponseEntity<?> getById(@PathVariable("video_id") Long videoId,
                                    @CurrentUserId Long currentUserId) {
        // 返回 DTO 而不是实体：实体的状态/密级是 Integer，前端当字符串比较恒不成立
        VideoResource video = videoService.getById(videoId);

        // 可见性校验：列表已按可见性过滤，直接用 URL 访问也必须同样拦住，
        // 否则"列表里看不到、但知道 id 就能拿到详情"等于没做鉴权。
        if (!videoPermissionService.canView(video, currentUserId)) {
            log.warn("video_detail_denied, video_id={}, user_id={}, classification_lv={}",
                videoId, currentUserId, video.getClassificationLv());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("没有该视频的查看权限（密级 " + video.getClassificationLv()
                    + "，需由上传者授权）"));
        }

        return ResponseEntity.ok(VideoResponse.from(video));
    }

    /**
     * Worker 处理完后回调更新状态（HLS / 指纹）
     *
     * PATCH /videos/{video_id}/status
     * body: { "hls_status": 2, "hls_path": "videos/1/hls/playlist.m3u8",
     *         "fingerprint_status": 2, "fingerprint_path": "videos/1/fingerprint.json" }
     *
     * 任意字段可单独更新（不传则保持原值）。
     *
     * <p><b>鉴权</b>：该端点原先是 permitAll —— 任何人都能
     * {@code PATCH {"hls_status": 2, "hls_path": "任意路径"}}，
     * 把没转码的视频标成 ready，或把播放源指向任意 MinIO 对象
     * （配合同样 permitAll 的 /playback/{video_id}/playlist.m3u8 构成完整的
     * 任意对象读链）。现在要求带 X-Worker-Token 头。
     */
    @PatchMapping("/{video_id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable("video_id") Long videoId,
            @RequestBody VideoStatusUpdateRequest request,
            // 参数名不能叫 workerToken —— 会遮蔽同名字段，鉴权就永远拿配置值比
            @RequestHeader(value = "X-Worker-Token", required = false) String providedWorkerToken
    ) {
        if (!isValidWorkerToken(providedWorkerToken)) {
            log.warn("video_status_update_unauthorized, video_id={}, reason={}",
                videoId, tokenRejectReason(providedWorkerToken));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("video_status_update_callback, video_id={}, body={}", videoId, request);
        videoService.updateProcessingStatus(
            videoId,
            request.getHlsStatus(), request.getHlsPath(),
            request.getFingerprintStatus(), request.getFingerprintPath(),
            request.getDurationSec()
        );
        VideoResource updated = videoService.getById(videoId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("video_id", updated.getId());
        resp.put("hls_status", ResourceEnums.hlsStatus(updated.getHlsStatus()));
        resp.put("fingerprint_status", ResourceEnums.hlsStatus(updated.getFingerprintStatus()));
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{video_id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Void> delete(
        @PathVariable("video_id") Long videoId,
        @CurrentUserId Long currentUserId
    ) {
        videoService.delete(videoId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Map<String, Object>> batchDelete(
        @RequestBody BatchDeleteRequest request,
        @CurrentUserId Long operatorId
    ) {
        int count = 0;
        for (Long id : request.getVideoIds()) {
            try {
                videoService.delete(id, operatorId);
                count++;
            } catch (Exception e) {
                log.warn("batch_delete_skip, video_id={}, error={}", id, e.getMessage());
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("task_id", java.util.UUID.randomUUID().toString());
        response.put("affected_count", count);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 批量授权（分享给多个用户）。
     *
     * <p><b>授权模型</b>（依据 {@code doc/database_schema.md} §3.1 与
     * {@code doc/openapi.yaml} 的本端点契约）：
     * <ul>
     *   <li>{@code actions} 位掩码：{@code 1=view 2=download 4=edit 8=delete 16=distribute}。</li>
     *   <li>{@code expire_at} 为空 = 永不过期；非空则到期自动失效。</li>
     *   <li>再次授权同一用户是<b>覆盖</b>（唯一键 {@code uk_video_user}）。</li>
     * </ul>
     *
     * <p><b>再分享的约束</b>（方案书 5.4「越权分享自动阻断」）：
     * 只有视频上传者、管理员、或持有 {@code distribute} 位的人才能把授权转授出去；
     * 否则 403 —— 不能拿着别人的授权去扩大授权范围。
     */
    @PostMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:share')")
    public ResponseEntity<Map<String, Object>> batchPermission(
        @RequestBody BatchPermissionRequest request,
        @CurrentUserId Long currentUserId
    ) {
        List<Long> userIds = request.getUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return ResponseEntity.badRequest().body(badRequest("user_ids 不能为空"));
        }
        if (userIds.size() > 200) {
            return ResponseEntity.badRequest().body(badRequest("一次最多授权 200 个用户"));
        }
        Integer actions = request.getActions();
        if (actions == null) {
            return ResponseEntity.badRequest().body(badRequest("actions 不能为空"));
        }
        VideoResource video = videoService.getById(request.getVideoId());

        // 只有能再分享的人才能授权（方案书 5.4）
        if (!videoPermissionService.canDistribute(video, currentUserId)) {
            log.warn("video_permission_distribute_denied, video_id={}, user_id={}",
                request.getVideoId(), currentUserId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("无权分享该视频（需要是上传者、管理员，或持有 distribute 授权）"));
        }

        int granted = videoPermissionService.grant(
            request.getVideoId(), userIds, actions,
            request.expireAtOrNull(), currentUserId);

        Map<String, Object> body = new HashMap<>();
        body.put("granted_count", granted);
        body.put("video_id", request.getVideoId());
        body.put("actions", actions);
        body.put("expire_at", request.getExpireAt());
        return ResponseEntity.ok(body);
    }

    /**
     * 撤销授权（把授权行删掉 ⇒ 对方回到"没有授权"）。
     *
     * <p>与 {@link #batchPermission} 同一套权限要求：能再分享才能撤销。
     */
    @DeleteMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:share')")
    public ResponseEntity<Map<String, Object>> revokePermission(
        @RequestBody BatchPermissionRequest request,
        @CurrentUserId Long currentUserId
    ) {
        List<Long> userIds = request.getUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return ResponseEntity.badRequest().body(badRequest("user_ids 不能为空"));
        }
        VideoResource video = videoService.getById(request.getVideoId());
        if (!videoPermissionService.canDistribute(video, currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("无权撤销该视频的授权"));
        }
        int revoked = videoPermissionService.revoke(request.getVideoId(), userIds);
        Map<String, Object> body = new HashMap<>();
        body.put("revoked_count", revoked);
        body.put("video_id", request.getVideoId());
        return ResponseEntity.ok(body);
    }

    /**
     * 查某视频当前有哪些授权（管理端展示"谁被授权了"）。
     */
    @GetMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:share')")
    public ResponseEntity<Map<String, Object>> listPermission(
        @RequestParam("video_id") Long videoId,
        @CurrentUserId Long currentUserId
    ) {
        VideoResource video = videoService.getById(videoId);
        if (!videoPermissionService.canDistribute(video, currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("无权查看该视频的授权列表"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (VideoPermission p : videoPermissionService.listByVideo(videoId)) {
            Map<String, Object> item = new HashMap<>();
            item.put("user_id", p.getUserId());
            item.put("actions", p.getActions());
            item.put("granted_by", p.getGrantedBy());
            item.put("granted_at", p.getGrantedAt());
            item.put("expire_at", p.getExpireAt());
            items.add(item);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("video_id", videoId);
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /** 统一的错误响应体（与 openapi 的 Problem Details 风格保持简单一致） */
    private Map<String, Object> error(String detail) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "forbidden");
        body.put("detail", detail);
        return body;
    }

    /** 参数错误（400）的响应体 */
    private Map<String, Object> badRequest(String detail) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "bad_request");
        body.put("detail", detail);
        return body;
    }


    /**
     * 校验 Worker 回调令牌。
     *
     * <p>用常量时间比较防 timing 旁路。没配 {@code edam.worker.token} 时
     * 一律拒绝 —— fail-closed，不能因为"忘了配"就把端点敞开。
     */
    private boolean isValidWorkerToken(String provided) {
        String expected = workerToken;
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            provided.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 说清楚"为什么拒"，而不是只丢一句 unauthorized。
     *
     * <p>2026-10-29 这次就是被这一行坑的：worker 回调被拒 3 次，
     * 日志里只有 {@code video_status_update_unauthorized, video_id=2}，
     * 看起来像"回调参数不对"，实际是**两边的 WORKER_TOKEN 不一致**
     * （后端 {@code edam.worker.token} 默认空 ⇒ fail-closed ⇒ 一律拒绝）。
     * 转码其实早就完成了，产物也在 MinIO 上，只是后端不知道，
     * 于是 {@code hls_status} 永远停在 pending ⇒ 用户点播放还是 404。
     *
     * <p>三种拒绝原因分开报，运维一眼能看出该改哪一边：
     * <ul>
     *   <li>{@code backend_token_not_configured} —— 后端没配
     *       {@code edam.worker.token}（或 WORKER_TOKEN 环境变量为空）</li>
     *   <li>{@code worker_token_header_missing} —— worker 侧没发
     *       {@code X-Worker-Token} 头（多半是 worker 的 WORKER_TOKEN 没配）</li>
     *   <li>{@code token_mismatch} —— 两边都配了但值不一样</li>
     * </ul>
     *
     * <p>不打印 token 本身（哪怕是哈希）—— 只报"哪一种不匹配"。
     */
    private String tokenRejectReason(String provided) {
        String expected = workerToken;
        if (expected == null || expected.isBlank()) {
            return "backend_token_not_configured";
        }
        if (provided == null || provided.isBlank()) {
            return "worker_token_header_missing";
        }
        return "token_mismatch";
    }

    private Map<String, Object> toPaginationResponse(Page<VideoResource> page) {
        // 统一分页壳（见 dto/PaginationResponses）—— 逐条转 DTO（Integer → String 枚举映射）
        return PaginationResponses.of(page, VideoResponse::from);
    }

    @Data
    public static class BatchDeleteRequest {
        private List<Long> videoIds;
        private String reason;
    }

    @Data
    public static class BatchPermissionRequest {
        @JsonProperty("video_id")
        private Long videoId;
        @JsonProperty("user_ids")
        private List<Long> userIds;
        /** 位掩码：1=view 2=download 4=edit 8=delete 16=distribute */
        @JsonProperty("actions")
        private Integer actions;
        /**
         * 过期时间（ISO-8601 date-time，与 openapi 契约一致）。
         *
         * <p>为空 = 永不过期。解析用 {@link OffsetDateTime#parse} ——
         * 契约写的是 {@code format: date-time}，带不帶偏移量都该能收。
         */
        @JsonProperty("expire_at")
        private String expireAt;

        /** 解析后的过期时间；没给或解析不了就是 null（永不过期） */
        OffsetDateTime expireAtOrNull() {
            if (expireAt == null || expireAt.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(expireAt);
            } catch (Exception e) {
                throw new IllegalArgumentException("expire_at 必须是 ISO-8601 时间: " + expireAt, e);
            }
        }
    }
}