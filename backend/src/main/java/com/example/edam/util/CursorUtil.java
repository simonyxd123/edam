package com.example.edam.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Cursor 分页工具（v3.2 V-4）
 *
 * 基于 last_id + limit 的稳定分页，替代传统 offset-based 分页。
 *
 * 优势：
 * - 性能稳定（O(log n) 索引扫描，不随页数增大变慢）
 * - 实时数据：新增/删除元素不影响翻页（对比 offset 在大页数时的跳页问题）
 * - 适合无限滚动场景
 *
 * 兼容策略：
 * - 旧 API（page + page_size）保留 6 个月
 * - 新 API（cursor + limit）通过 ?cursor=xxx&limit=N 调用
 * - 响应中包含 next_cursor（Base64 编码）+ has_more
 *
 * Cursor 格式：Base64({"id": 12345, "ts": 1234567890})
 */
public final class CursorUtil {

    private CursorUtil() {}

    /**
     * 编码游标（id + 时间戳，避免重复 id 问题）
     *
     * @param id 主键 ID
     * @param ts 时间戳（毫秒，可选；用于稳定排序）
     * @return Base64 编码字符串
     */
    public static String encode(Long id, Long ts) {
        if (id == null) return null;
        String raw = id + ":" + (ts != null ? ts : System.currentTimeMillis());
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标
     *
     * @param cursor Base64 字符串
     * @return CursorParts{id, ts} 或 null（解析失败）
     */
    public static CursorParts decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0) return null;
            long id = Long.parseLong(raw.substring(0, sep));
            long ts = Long.parseLong(raw.substring(sep + 1));
            return new CursorParts(id, ts);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 标准 cursor 分页响应
     */
    public static java.util.Map<String, Object> buildResponse(
            java.util.List<?> items, long lastId, long lastTs, int limit) {
        boolean hasMore = items.size() >= limit;
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("items", items);
        response.put("next_cursor", hasMore ? encode(lastId, lastTs) : null);
        response.put("has_more", hasMore);
        response.put("limit", limit);
        return response;
    }

    /**
     * 解码后的 cursor 字段
     */
    public record CursorParts(long id, long ts) {}
}