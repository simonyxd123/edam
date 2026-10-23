package com.example.edam.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link PaginationResponses} 单测。
 *
 * <p>这个壳以前被抄了 7 份；前端 {@code PageResult<T>} 依赖它的字段名，
 * 改错字段名就是某个页面拿不到 items。
 */
class PaginationResponsesTest {

    @Test
    @DisplayName("分页壳的字段名与前端 PageResult 对齐")
    void shapeMatchesFrontendContract() {
        Page<String> page = new Page<>(2, 20);
        page.setRecords(List.of("a", "b"));
        page.setTotal(42);
        page.setPages(3);

        Map<String, Object> resp = PaginationResponses.of(page);

        assertInstanceOf(List.class, resp.get("items"));
        assertEquals(List.of("a", "b"), resp.get("items"));

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) resp.get("pagination");
        assertEquals(2, pagination.get("page"));
        assertEquals(20, pagination.get("page_size"));
        assertEquals(42L, pagination.get("total"));
        assertEquals(3, pagination.get("total_pages"));
    }

    @Test
    @DisplayName("带 mapper 时逐条转换")
    void appliesMapper() {
        Page<String> page = new Page<>(1, 10);
        page.setRecords(List.of("a"));
        page.setTotal(1);
        page.setPages(1);

        Map<String, Object> resp = PaginationResponses.of(page, s -> "T:" + s);
        assertEquals(List.of("T:a"), resp.get("items"));
    }

    @Test
    @DisplayName("直接给列表也能构造")
    void fromList() {
        Map<String, Object> resp = PaginationResponses.of(List.of("x", "y"), 1, 10, 5, 1);
        assertEquals(List.of("x", "y"), resp.get("items"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) resp.get("pagination");
        assertEquals(5L, pagination.get("total"));
    }
}
