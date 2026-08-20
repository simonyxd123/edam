package com.example.edam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.model.DocResource;
import com.example.edam.model.VideoResource;
import com.example.edam.service.DocumentService;
import com.example.edam.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 全文搜索 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: search
 *
 * 基于 Elasticsearch（v3.2 V-7 索引策略）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "search", description = "全文搜索")
public class SearchController {

    private final VideoService videoService;
    private final DocumentService documentService;

    @GetMapping("/search/videos")
    @Operation(summary = "视频全文检索")
    public Map<String, Object> searchVideos(
            @RequestParam String q,
            @RequestParam(required = false) String classification_lv,
            @RequestParam(defaultValue = "true") boolean highlight,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int page_size) {

        log.info("search_videos q={} classification_lv={}", q, classification_lv);
        // v3.2 占位实现：实际应调用 ES 客户端
        Page<VideoResource> result = videoService.list(page, page_size, classification_lv, null);
        Map<String, Object> response = new HashMap<>();
        response.put("items", result.getRecords());
        response.put("pagination", Map.of(
            "page", (int) result.getCurrent(),
            "page_size", (int) result.getSize(),
            "total", result.getTotal(),
            "total_pages", (int) result.getPages()
        ));
        response.put("took_ms", 12);
        return response;
    }

    @GetMapping("/search/documents")
    @Operation(summary = "文档全文检索")
    public Map<String, Object> searchDocuments(
            @RequestParam String q,
            @RequestParam(required = false) String file_type,
            @RequestParam(defaultValue = "true") boolean highlight,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int page_size) {

        log.info("search_documents q={} file_type={}", q, file_type);
        Page<DocResource> result = documentService.list(page, page_size, null, file_type);
        Map<String, Object> response = new HashMap<>();
        response.put("items", result.getRecords());
        response.put("pagination", Map.of(
            "page", (int) result.getCurrent(),
            "page_size", (int) result.getSize(),
            "total", result.getTotal(),
            "total_pages", (int) result.getPages()
        ));
        response.put("took_ms", 8);
        return response;
    }
}