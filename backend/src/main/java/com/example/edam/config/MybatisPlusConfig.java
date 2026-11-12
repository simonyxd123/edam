package com.example.edam.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MyBatis-Plus 分页插件配置。
 *
 * <p><b>为什么必须有这个类</b>（2026-11-11 代码审计第四轮 §1 定级 critical）：
 *
 * <p>MyBatis-Plus 的 {@code selectPage(IPage, Wrapper)} <b>本身不做 LIMIT</b>。
 * LIMIT / OFFSET / COUNT 是 {@link PaginationInnerInterceptor} 在
 * SQL 拦截层改写出来的。没有这个拦截器，{@code selectPage} 会退化成
 * 普通 {@code selectList}：
 *
 * <ul>
 *   <li>返回<b>全表</b>（不是当前页）——{@code page_size} 参数彻底失效</li>
 *   <li>{@code page.getTotal()} 恒为 0 ⇒ 前端分页器显示"共 0 条"</li>
 *   <li>{@code page.getPages()} 恒为 0</li>
 * </ul>
 *
 * <p>而且 MP 的自动装配
 * （{@code MybatisPlusInnerInterceptorAutoConfiguration}）带
 * {@code @ConditionalOnBean(InnerInterceptor.class)} ——
 * <b>应用不自己声明 {@code InnerInterceptor} bean，它就不会被创建</b>。
 * 所以这个类必须显式写出来，不能指望 starter 自动带上。
 *
 * <p><b>为什么这个 bug 能潜伏这么久</b>：
 * <ul>
 *   <li>CI 跑的是 {@code mvn test}，而后端测试全部连真 MySQL，
 *       但现有测试全是 mock repository，{@code selectPage} 根本不执行 SQL</li>
 *   <li>没有任何一条测试断言过 {@code records.size() == pageSize}</li>
 *   <li>数据量小的时候，全表和分页返回的是同一批记录，人眼看不出差别</li>
 * </ul>
 * ⇒ 配套回归测试见
 * {@code src/test/java/com/example/edam/config/MybatisPlusConfigTest.java}。
 *
 * <p><b>maxLimit 的作用</b>：最后一道闸。
 * 即使某处忘了夹 {@code page_size}，SQL 也不会一次拉 100 万行。
 * 正常列表端点另有 {@code PageParams.clampSize} 夹到 1~100，
 * 这里兜的是"漏夹"的情况。
 *
 * <p><b>overflow=false</b>：请求的页码超出总页数时，
 * 返回<b>空列表</b>而不是回退到第一页。
 * 回退到第一页会让"翻到最后一页"变成"看到第一页"，
 * 用户完全意识不到自己看的是错的页。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 单次查询的最大返回行数（兜底闸门）。
     *
     * <p>取值 1000：正常列表端点上限是 100（{@code PageParams.MAX_PAGE_SIZE}），
     * 这里留 10 倍余量给"忘了夹"的场景，同时保证不会一次拉爆内存。
     *
     * <p>注意：审计导出走的是 {@code AuditService.exportCsv}，
     * 它用 {@code wrapper.last("LIMIT ...")} 而非 {@code selectPage}，
     * 不受这个闸门影响（见该方法的注释）。
     */
    public static final long MAX_QUERY_ROWS = 1000L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_QUERY_ROWS);
        // 页码越界时返回空列表，而不是回退到第一页
        pagination.setOverflow(false);

        interceptor.setInterceptors(List.of(pagination));
        return interceptor;
    }
}
