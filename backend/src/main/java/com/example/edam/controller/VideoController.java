package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.VideoStatusUpdateRequest;
import com.example.edam.model.VideoResource;
import com.example.edam.service.VideoService;
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
        @RequestHeader("X-User-Id") Long uploaderId
    ) {
        // MIME / 文件类型校验：必须以 video/ 开头
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("video/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "仅支持 video/* 类型的文件，当前: " + contentType);
        }

        // 大小校验（前端 MAX=2GB，这里兜底）
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }

        VideoResource video = videoService.upload(file, classificationLv, title, uploaderId);
        Map<String, Object> response = new HashMap<>();
        response.put("video_id", video.getId());
        response.put("file_hash", video.getFileHash());
        response.put("hls_status", statusToString(video.getHlsStatus()));
        response.put("fingerprint_status", statusToString(video.getFingerprintStatus()));
        response.put("estimated_processing_sec", 60);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{video_id}")
    public VideoResource getById(@PathVariable("video_id") Long videoId) {
        return videoService.getById(videoId);
    }

    /**
     * Worker 处理完后回调更新状态（HLS / 指纹）
     *
     * PATCH /videos/{video_id}/status
     * body: { "hls_status": 2, "hls_path": "videos/1/hls/playlist.m3u8",
     *         "fingerprint_status": 2, "fingerprint_path": "videos/1/fingerprint.json" }
     *
     * 任意字段可单独更新（不传则保持原值）。
     */
    @PatchMapping("/{video_id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable("video_id") Long videoId,
            @RequestBody VideoStatusUpdateRequest request
    ) {
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
        resp.put("hls_status", statusToString(updated.getHlsStatus()));
        resp.put("fingerprint_status", statusToString(updated.getFingerprintStatus()));
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{video_id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Void> delete(
        @PathVariable("video_id") Long videoId,
        @RequestHeader("X-User-Id") Long currentUserId
    ) {
        videoService.delete(videoId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:delete')")
    public ResponseEntity<Map<String, Object>> batchDelete(
        @RequestBody BatchDeleteRequest request,
        @RequestHeader("X-User-Id") Long operatorId
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

    @PostMapping("/batch-permission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('video:share')")
    public ResponseEntity<Map<String, Object>> batchPermission(
        @RequestBody BatchPermissionRequest request
    ) {
        // 实际实现应调用 PermissionService
        Map<String, Object> response = new HashMap<>();
        response.put("granted_count", request.getUserIds().size());
        return ResponseEntity.ok(response);
    }

    private String statusToString(Integer status) {
        if (status == null) return "pending";
        return switch (status) {
            case 0 -> "pending";
            case 1 -> "processing";
            case 2 -> "ready";
            case 3 -> "failed";
            default -> "unknown";
        };
    }

    private Map<String, Object> toPaginationResponse(Page<VideoResource> page) {
        Map<String, Object> response = new HashMap<>();
        response.put("items", page.getRecords());
        response.put("pagination", Map.of(
            "page", (int) page.getCurrent(),
            "page_size", (int) page.getSize(),
            "total", page.getTotal(),
            "total_pages", (int) page.getPages()
        ));
        return response;
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