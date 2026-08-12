package com.example.edam.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    /**
     * 提取水印/指纹
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(
        @RequestParam("file") MultipartFile file,
        @RequestParam("type") String type,
        @RequestHeader("X-User-Id") Long operatorId
    ) {
        log.info("watermark_extract_request, type={}, size={}, operator_id={}",
            type, file.getSize(), operatorId);

        // 实际生产：调用 Worker 提取 + 比对
        // 这里返回 mock 结果
        Map<String, Object> extracted = new HashMap<>();
        extracted.put("employee_no", "");
        extracted.put("extract_time", java.time.OffsetDateTime.now().toString());
        extracted.put("fingerprint", "");
        extracted.put("confidence", 0.0);

        // 模拟匹配结果（实际生产从 DB 比对）
        List<Map<String, Object>> matchedUsers = new ArrayList<>();

        Map<String, Object> response = new HashMap<>();
        response.put("type", type);
        response.put("extracted", extracted);
        response.put("matched_users", matchedUsers);

        return ResponseEntity.ok(response);
    }

    /**
     * 水印缓存查询
     */
    @GetMapping("/cache")
    public List<Map<String, Object>> listCache(
        @RequestParam("resource_id") Long resourceId,
        @RequestParam("resource_type") String resourceType
    ) {
        // 实际生产：从 Redis 查询
        return List.of();
    }

    /**
     * 清除水印缓存
     */
    @DeleteMapping("/cache/{cache_id}")
    public ResponseEntity<Void> deleteCache(@PathVariable("cache_id") Long cacheId) {
        // 实际生产：从 Redis 删除
        log.info("watermark_cache_invalidated, cache_id={}", cacheId);
        return ResponseEntity.noContent().build();
    }
}