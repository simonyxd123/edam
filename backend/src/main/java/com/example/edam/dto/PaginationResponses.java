package com.example.edam.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 统一的分页响应结构。
 *
 * <p><b>为什么抽这个</b>：仓库里同一个分页响应壳被抄了 7 份
 * （VideoController / DocumentController / DistributionController /
 * UserController / AuditController / SearchController ×2），
 * 逐字相同。改一次字段名要同步改 7 处，漏一处就是前端某个页面
 * 拿不到 items。
 *
 * <p>结构（与前端 {@code PageResult<T>} 对齐）：
 * <pre>{@code
 * {
 *   "items": [...],
 *   "pagination": { "page": 1, "page_size": 20, "total": 100, "total_pages": 5 }
 * }
 * }</pre>
 */
public final class PaginationResponses {

    private PaginationResponses() {
    }

    /**
     * 直接返回 Page 里的记录。
     */
    public static Map<String, Object> of(Page<?> page) {
        return of(page, null);
    }

    /**
     * 逐条转换后再返回（entity → DTO）。
     *
     * @param page    分页结果
     * @param mapper  转换函数；传 null 表示不转换
     */
    public static <T, R> Map<String, Object> of(Page<T> page, Function<T, R> mapper) {
        List<?> items = mapper == null
            ? List.copyOf(page.getRecords())
            : page.getRecords().stream().map(mapper).toList();
        return build(items, page.getCurrent(), page.getSize(),
            page.getTotal(), page.getPages());
    }

    /**
     * 直接给列表 + 分页参数（用于非 Page 来源的查询）。
     */
    public static <T> Map<String, Object> of(List<T> items, long page, long pageSize,
                                            long total, long totalPages) {
        return build(items, page, pageSize, total, totalPages);
    }

    private static Map<String, Object> build(List<?> items, long page, long pageSize,
                                             long total, long totalPages) {
        return Map.of(
            "items", items,
            "pagination", Map.of(
                "page", (int) page,
                "page_size", (int) pageSize,
                "total", total,
                "total_pages", (int) totalPages
            )
        );
    }
}
