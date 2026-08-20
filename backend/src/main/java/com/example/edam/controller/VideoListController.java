package com.example.edam.controller;

import com.example.edam.model.VideoResource;
import com.example.edam.service.VideoService;
import com.example.edam.util.CursorUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 视频列表 Controller（v3.2 V-4 cursor 分页版）
 *
 * 与 VideoController 并存，提供 cursor 分页接口
 * 旧 VideoController 保留 6 个月向后兼容
 */
@Slf4j
@RestController
@RequestMapping("/v2/videos")
@RequiredArgsConstructor
@Tag(name = "videos", description = "视频资源（v3.2 cursor 分页）")
public class VideoListController {

    private final VideoService videoService;

    /**
     * Cursor 分页列表（推荐）
     *
     * 调用示例： GET /v2/videos?limit=20&cursor=MTIzNDU6MTczMTcyMDAwMDAw
     * 第一页：        GET /v2/videos?limit=20
     */
    @GetMapping
    @Operation(summary = "视频资源列表（cursor 分页）")
    public Map<String, Object> listCursor(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String classification_lv,
            @RequestParam(required = false) Long uploader_id) {

        // 限制 limit 范围（防御性）
        limit = Math.max(1, Math.min(limit, 100));

        CursorUtil.CursorParts parts = CursorUtil.decode(cursor);
        List<VideoResource> items = videoService.listByCursor(parts, limit, classification_lv, uploader_id);

        long lastId = items.isEmpty() ? 0L : items.get(items.size() - 1).getId();
        long lastTs = items.isEmpty() ? 0L
            : items.get(items.size() - 1).getUploadTime() != null
                ? items.get(items.size() - 1).getUploadTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                : System.currentTimeMillis();

        log.info("video_list_cursor cursor={} limit={} count={}", cursor, limit, items.size());
        return CursorUtil.buildResponse(items, lastId, lastTs, limit);
    }
}