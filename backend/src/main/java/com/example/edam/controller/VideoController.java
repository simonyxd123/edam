package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.ResourceEnums;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.dto.VideoResponse;
import com.example.edam.dto.VideoStatusUpdateRequest;
import com.example.edam.model.VideoResource;
import com.example.edam.service.VideoService;
import com.example.edam.security.CurrentUserId;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    public VideoResponse getById(@PathVariable("video_id") Long videoId) {
        // 返回 DTO 而不是实体：实体的状态/密级是 Integer，前端当字符串比较恒不成立
        return VideoResponse.from(videoService.getById(videoId));
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
            log.warn("video_status_update_unauthorized, video_id={}", videoId);
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
     * <p><b>状态：未实现</b>。原实现直接返回
     * {@code granted_count = userIds.size()} —— 看起来全部授权成功，
     * 实际一条权限都没写。前端会提示"已授权 N 个用户"，而权限表纹丝不动。
     *
     * <p>需要先明确授权模型（是否带有效期 / 是否可撤销 / 是否受密级约束 /
     * 被授权者能否再分享），才能落库到 {@code video_permission}。
     * 在那之前明确返回 501，不给前端假成功。
     */
    @PostMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:share')")
    public ResponseEntity<Map<String, Object>> batchPermission(
        @RequestBody BatchPermissionRequest request
    ) {
        log.warn("batch_permission_not_implemented, user_ids={}", request.getUserIds().size());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "not_implemented");
        body.put("detail", "批量授权尚未实现：需要先确定授权模型（有效期 / 可撤销性 / "
            + "密级约束 / 是否可再分享），再落库到 video_permission。"
            + "为避免前端误以为授权成功，此处明确返回 501。");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
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
        private Long videoId;
        private List<Long> userIds;
        private Integer actions;
        private String expireAt;
    }
}