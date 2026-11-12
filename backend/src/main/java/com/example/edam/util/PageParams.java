package com.example.edam.util;

/**
 * 分页参数夹取工具。
 *
 * <p><b>为什么需要它</b>（2026-11-11 代码审计第四轮 §1）：
 *
 * <p>分页插件补上之前，所有列表端点的 {@code page_size} 都是**完全无效**的
 * （{@code selectPage} 退化成全表查询）。插件补上之后它开始真正生效 ——
 * 于是"前端传 {@code page_size=100000}"就会变成一个真实的
 * "一次拉 10 万行"的请求。
 *
 * <p>两层防护：
 * <ol>
 *   <li>本类：每个列表端点显式夹到 {@code 1~100}</li>
 *   <li>{@code MybatisPlusConfig} 的 {@code maxLimit=1000}：
 *       兜住"漏夹"的地方</li>
 * </ol>
 *
 * <p>注意 {@code page} 也要夹：{@code page=0} 或负数会让
 * {@code OFFSET} 变成负数 —— MySQL 报
 * {@code Incorrect offset}，而 PostgreSQL 会直接报错。
 * 而且 {@code page=0} 在 SQL 语义里等价于"不分页"，同样是全表。
 */
public final class PageParams {

    private PageParams() {
    }

    /** 单页最大条数 —— 与前端分页器的最大选项一致。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 单页最小条数。 */
    public static final int MIN_PAGE_SIZE = 1;

    /**
     * 夹住页码：小于 1 一律当第 1 页。
     *
     * @param page 请求参数里的页码
     * @return >= 1 的页码
     */
    public static int clampPage(int page) {
        return Math.max(1, page);
    }

    /**
     * 夹住每页条数：{@code 1~100}。
     *
     * <p>上界 100 的理由：审计导出的上限是 5 万行（{@code AuditService.MAX_EXPORT_ROWS}），
     * 而列表页给人看的，100 条已经足够。上界不只是防 DoS ——
     * 它还保证了分页器的"总页数"是用户能接受的量级。
     *
     * @param pageSize 请求参数里的每页条数
     * @return {@code 1~100} 之间的条数
     */
    public static int clampSize(int pageSize) {
        return Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, pageSize));
    }
}
