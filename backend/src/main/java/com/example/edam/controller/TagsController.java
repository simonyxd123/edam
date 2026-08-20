package com.example.edam.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 标签管理 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: tags
 *
 * 注：v3.2 占位实现，使用内存存储；生产应使用持久化存储（Repository + DB）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "tags", description = "标签管理")
public class TagsController {

    private static final ConcurrentHashMap<Long, Tag> TAGS = new ConcurrentHashMap<>();
    private static final AtomicLong ID_GEN = new AtomicLong(1);

    static {
        // 预置标签
        TAGS.put(1L, new Tag(1L, "机密", "confidential", "both", "#FF0000", 0));
        TAGS.put(2L, new Tag(2L, "财务", "finance", "document", "#0000FF", 0));
        TAGS.put(3L, new Tag(3L, "人事", "hr", "document", "#00AA00", 0));
    }

    @GetMapping("/tags")
    @Operation(summary = "标签列表")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        List<Map<String, Object>> result = new ArrayList<>();
        TAGS.values().stream()
            .filter(t -> type == null || type.equals(t.type))
            .filter(t -> keyword == null || t.name.contains(keyword))
            .forEach(t -> result.add(t.toMap()));
        return result;
    }

    @PostMapping("/tags")
    @Operation(summary = "创建标签")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateTagRequest request) {
        long id = ID_GEN.incrementAndGet();
        Tag tag = new Tag(id, request.name, request.code, request.type, request.color, 0);
        TAGS.put(id, tag);
        return ResponseEntity.status(201).body(tag.toMap());
    }

    @GetMapping("/tags/{tag_id}")
    @Operation(summary = "标签详情")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable("tag_id") Long tagId) {
        Tag t = TAGS.get(tagId);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t.toMap());
    }

    @DeleteMapping("/tags/{tag_id}")
    @Operation(summary = "删除标签")
    public ResponseEntity<Void> delete(@PathVariable("tag_id") Long tagId) {
        TAGS.remove(tagId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/videos/{video_id}/tags")
    @Operation(summary = "给视频添加标签")
    public ResponseEntity<Map<String, Object>> addToVideo(
            @PathVariable("video_id") Long videoId,
            @RequestBody Map<String, List<Long>> body) {
        log.info("video_tag_added video_id={} tag_ids={}", videoId, body.get("tag_ids"));
        return ResponseEntity.ok(Map.of("video_id", videoId, "tag_ids", body.get("tag_ids")));
    }

    @DeleteMapping("/videos/{video_id}/tags")
    @Operation(summary = "从视频移除标签")
    public ResponseEntity<Void> removeFromVideo(@PathVariable("video_id") Long videoId) {
        log.info("video_tag_removed video_id={}", videoId);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class CreateTagRequest {
        private String name;
        private String type;       // video / document / both
        private String code;       // URL 友好的英文代号
        private String color;      // #RRGGBB
    }

    private record Tag(Long id, String name, String code, String type, String color, int useCount) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("code", code);
            m.put("type", type);
            m.put("color", color);
            m.put("use_count", useCount);
            return m;
        }
    }
}