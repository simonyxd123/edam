package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edam.model.VideoTag;
import com.example.edam.repository.TagRepository;
import com.example.edam.repository.VideoTagRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 标签管理 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: tags
 *
 * <p><b>存储</b>：表 {@code tag} 一直在 schema 里，但此前没有 model，
 * 整个功能用 {@code static ConcurrentHashMap} 实现 ——
 * 重启即丢、多实例不共享、任何登录用户都能增删全局标签。
 * 现在改为真正的 DB 存储（Repository）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "tags", description = "标签管理")
public class TagsController {

    private final TagRepository tagRepository;
    private final VideoTagRepository videoTagRepository;

    @GetMapping("/tags")
    @Operation(summary = "标签列表")
    public List<com.example.edam.model.Tag> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<com.example.edam.model.Tag> wrapper = new LambdaQueryWrapper<>();
        if (type != null && !type.isBlank()) {
            wrapper.eq(com.example.edam.model.Tag::getType, type);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(com.example.edam.model.Tag::getName, keyword);
        }
        wrapper.orderByAsc(com.example.edam.model.Tag::getId);
        return tagRepository.selectList(wrapper);
    }

    @PostMapping("/tags")
    @Operation(summary = "创建标签")
    @PreAuthorize("hasAuthority('tag:manage')")
    @Transactional
    public ResponseEntity<com.example.edam.model.Tag> create(@RequestBody CreateTagRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        com.example.edam.model.Tag tag = new com.example.edam.model.Tag();
        tag.setName(request.getName().trim());
        tag.setCode(request.getCode());
        tag.setType(request.getType() == null ? "both" : request.getType());
        tag.setColor(request.getColor());
        tag.setUseCount(0);
        tagRepository.insert(tag);
        return ResponseEntity.status(201).body(tag);
    }

    @GetMapping("/tags/{tag_id}")
    @Operation(summary = "标签详情")
    public ResponseEntity<com.example.edam.model.Tag> getById(@PathVariable("tag_id") Long tagId) {
        com.example.edam.model.Tag t = tagRepository.selectById(tagId);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    @DeleteMapping("/tags/{tag_id}")
    @Operation(summary = "删除标签")
    @PreAuthorize("hasAuthority('tag:manage')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("tag_id") Long tagId) {
        tagRepository.deleteById(tagId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 给视频添加标签。
     *
     * <p><b>状态</b>：写 {@code video_tag} 关联表。关联表
     * {@code video_tag(id, video_id, tag_id)} 已在 schema 里。
     */
    @PostMapping("/videos/{video_id}/tags")
    @Operation(summary = "给视频添加标签")
    @PreAuthorize("hasAuthority('video:edit')")
    @Transactional
    public ResponseEntity<Map<String, Object>> addToVideo(
            @PathVariable("video_id") Long videoId,
            @RequestBody Map<String, List<Long>> body) {
        List<Long> tagIds = body.get("tag_ids");
        if (tagIds == null || tagIds.isEmpty()) {
            throw new IllegalArgumentException("tag_ids 不能为空");
        }
        // 校验标签都存在，避免写入悬空关联
        for (Long tagId : tagIds) {
            if (tagRepository.selectById(tagId) == null) {
                throw new IllegalArgumentException("标签不存在: " + tagId);
            }
        }
        // 写关联表（uk_video_tag 保证幂等：重复添加会撞唯一键 → 先查后写）
        for (Long tagId : tagIds) {
            boolean exists = videoTagRepository.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VideoTag>()
                    .eq(VideoTag::getVideoId, videoId)
                    .eq(VideoTag::getTagId, tagId)) > 0;
            if (!exists) {
                VideoTag vt = new VideoTag();
                vt.setVideoId(videoId);
                vt.setTagId(tagId);
                videoTagRepository.insert(vt);
            }
        }
        log.info("video_tag_added video_id={} tag_ids={}", videoId, tagIds);
        return ResponseEntity.ok(Map.of("video_id", videoId, "tag_ids", tagIds));
    }

    @DeleteMapping("/videos/{video_id}/tags")
    @Operation(summary = "从视频移除标签")
    @PreAuthorize("hasAuthority('video:edit')")
    @Transactional
    public ResponseEntity<Void> removeFromVideo(@PathVariable("video_id") Long videoId) {
        videoTagRepository.delete(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VideoTag>()
                .eq(VideoTag::getVideoId, videoId));
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
}
