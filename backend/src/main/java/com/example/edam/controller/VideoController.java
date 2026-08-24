package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.model.VideoResource;
import com.example.edam.service.VideoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频资源 Controller
 * 对应 openapi.yaml tag: videos
 */
@RestController
@RequestMapping("/videos")
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
    public ResponseEntity<Map<String, Object>> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam("classification_lv") String classificationLv,
        @RequestParam(value = "title", required = false) String title,
        @RequestHeader("X-User-Id") Long uploaderId
    ) {
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

    @DeleteMapping("/{video_id}")
    public ResponseEntity<Void> delete(
        @PathVariable("video_id") Long videoId,
        @RequestHeader("X-User-Id") Long currentUserId
    ) {
        videoService.delete(videoId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
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