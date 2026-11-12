package com.example.edam.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.edam.util.PageParams;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页插件的回归测试（2026-11-11 代码审计第四轮 §1，定级 critical）。
 *
 * <p><b>这个测试为什么必须存在</b>：
 *
 * <p>分页插件<b>从未被注册过</b>。MyBatis-Plus 的
 * {@code selectPage(IPage, Wrapper)} 本身不做 LIMIT ——
 * LIMIT 是 {@link PaginationInnerInterceptor} 在 SQL 拦截层改写出来的。
 * 没有拦截器时，{@code selectPage} 退化成普通 {@code selectList}：
 * <ul>
 *   <li>返回<b>全表</b>，{@code page_size} 参数彻底失效</li>
 *   <li>{@code total} 恒为 0 ⇒ 前端分页器显示"共 0 条"</li>
 * </ul>
 *
 * <p>而 MP 的自动装配带 {@code @ConditionalOnBean(InnerInterceptor.class)} ——
 * 应用不自己声明 bean，它就不会被创建。
 *
 * <p><b>为什么三轮审计都没发现</b>：
 * <ul>
 *   <li>现有测试全部 mock repository，{@code selectPage} 根本不执行 SQL</li>
 *   <li>没有任何一条测试断言过 {@code records.size() == pageSize}</li>
 *   <li>数据量小的时候，全表和分页返回的是同一批记录，人眼看不出差别</li>
 * </ul>
 *
 * <p><b>为什么用 H2 而不是真 MySQL</b>：
 * 这个测试要验证的是<b>分页机制本身</b>（LIMIT 有没有被下推到 SQL），
 * 不涉及 MySQL 方言。H2 让它<b>不需要外部服务</b>就能跑 ——
 * 而"CI 里跑不起来"的测试等于没有测试。
 * 真 MySQL 上的 Flyway 迁移验证由 {@code ApplicationStartupTest} 负责。
 *
 * <p><b>关键点</b>：本测试用的是<b>生产代码里的那个配置类</b>
 * （{@code new MybatisPlusConfig().mybatisPlusInterceptor()}），
 * 不是在测试里另写一份拦截器 —— 否则"生产配置被改坏了"照样测不出来。
 */
@DisplayName("MyBatis-Plus 分页插件")
class MybatisPlusConfigTest {

    /**
     * 表里的行数。
     *
     * <p><b>为什么是 2000 而不是 25</b>：验证 maxLimit 截断需要"行数 &gt; maxLimit"。
     * 表里只有 25 行时，{@code LIMIT 1000} 和 {@code LIMIT 50001} 取出来的结果一样，
     * 测不出截断 —— 测试会"因为数据不够"而假通过。
     * 2000 &gt; maxLimit(1000) ⇒ 截断与否有明确差异。
     */
    private static final int TOTAL_ROWS = 2000;

    @TableName("demo")
    static class Demo {
        @TableId
        private Long id;
        private String title;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    interface DemoMapper extends BaseMapper<Demo> {
        @Select("SELECT id, title FROM demo")
        List<Demo> rawAll();
    }

    private static DemoMapper mapper;

    @BeforeAll
    static void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pagination_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");

        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE demo(id BIGINT PRIMARY KEY, title VARCHAR(50))");
            for (int i = 1; i <= TOTAL_ROWS; i++) {
                st.execute("INSERT INTO demo VALUES(" + i + ",'t" + i + "')");
            }
        }

        MybatisConfiguration cfg = new MybatisConfiguration(
            new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.addMapper(DemoMapper.class);
        cfg.setMapUnderscoreToCamelCase(true);

        // **用生产配置类**（不是测试里另写一份）
        cfg.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setConfiguration(cfg);
        mapper = factoryBean.getObject().openSession().getMapper(DemoMapper.class);
    }

    // ==================================================================
    // 1. 核心回归：分页必须真的发生
    // ==================================================================

    @Test
    @DisplayName("selectPage 必须真的分页（10 条 / 共 2000 条），而不是返回全表")
    void selectPageActuallyPaginates() {
        Page<Demo> page = mapper.selectPage(
            new Page<>(1, 10), new LambdaQueryWrapper<Demo>());

        assertEquals(10, page.getRecords().size(),
            "selectPage 必须只返回第一页的 10 条 —— "
                + "返回 " + page.getRecords().size() + " 条说明分页插件没注册，"
                + "selectPage 退化成了全表查询（这正是第四轮审计的 critical 缺陷）");

        assertEquals(TOTAL_ROWS, page.getTotal(),
            "total 必须是真实总数 " + TOTAL_ROWS + " —— 恒为 0 说明 count 查询也没生效，"
                + "前端分页器会显示\"共 0 条\"");

        assertEquals(200, page.getPages(),
            TOTAL_ROWS + " 条 / 每页 10 条 = 200 页");
    }

    @Test
    @DisplayName("第二页返回的是另外 10 条，不是第一页的重复")
    void secondPageReturnsDifferentRows() {
        Page<Demo> p1 = mapper.selectPage(new Page<>(1, 10), new LambdaQueryWrapper<Demo>());
        Page<Demo> p2 = mapper.selectPage(new Page<>(2, 10), new LambdaQueryWrapper<Demo>());

        assertEquals(10, p2.getRecords().size());
        assertTrue(p1.getRecords().stream().noneMatch(
                a -> p2.getRecords().stream().anyMatch(b -> b.getId().equals(a.getId()))),
            "第 1 页和第 2 页不应有重复记录 —— 有重复说明 OFFSET 没生效");
    }

    @Test
    @DisplayName("页码超出总页数时返回空列表，而不是回退到第一页")
    void pageBeyondLastReturnsEmpty() {
        // 总共 200 页（2000 条 / 每页 10 条）⇒ 第 999 页不存在
        Page<Demo> page = mapper.selectPage(new Page<>(999, 10), new LambdaQueryWrapper<Demo>());

        assertTrue(page.getRecords().isEmpty(),
            "翻到不存在的页应返回空列表 —— 回退到第一页会让用户以为看的是最后一页");
        assertEquals(TOTAL_ROWS, page.getTotal());
    }

    // ==================================================================
    // 2. maxLimit 兜底
    // ==================================================================

    @Test
    @DisplayName("maxLimit 兜底：忘了夹的 page_size 不会一次拉爆")
    void maxLimitCapsOversizedPage() {
        // 不夹 page_size，直接给一个巨大的值
        Page<Demo> page = mapper.selectPage(
            new Page<>(1, 100_000), new LambdaQueryWrapper<Demo>());

        assertTrue(page.getRecords().size() <= MybatisPlusConfig.MAX_QUERY_ROWS,
            "page_size 超过 maxLimit 时必须被截断到 " + MybatisPlusConfig.MAX_QUERY_ROWS
                + " 条，实际 " + page.getRecords().size() + " 条");
    }

    @Test
    @DisplayName("审计导出的 Page.setMaxLimit 能覆盖全局 maxLimit（否则导出被静默截断）")
    void perPageMaxLimitOverridesGlobalMaxLimit() {
        // 审计导出走的是 Page(1, MAX_EXPORT_ROWS+1) 且 setSearchCount(false)。
        // 全局 maxLimit=1000 会把它截断成 1000 行 ⇒ 5 万行的上限保护
        // 变成"最多只能导出 1000 行而且不报错"（静默截断）。
        // setMaxLimit 的优先级必须高于全局值。
        Page<Demo> page = new Page<>(1, 50_001);
        page.setSearchCount(false);
        page.setMaxLimit(50_001L);

        List<Demo> records = mapper.selectPage(page, new LambdaQueryWrapper<Demo>()).getRecords();

        assertEquals(TOTAL_ROWS, records.size(),
            "覆盖 maxLimit 后应取到全部 " + TOTAL_ROWS + " 行 —— "
                + "只取到 " + records.size() + " 行说明被全局 maxLimit 截断了");
    }

    @Test
    @DisplayName("不覆盖 maxLimit 时确实会被截断（证明上一条不是碰巧过的）")
    void withoutOverrideItIsTruncated() {
        Page<Demo> page = new Page<>(1, 50_001);
        page.setSearchCount(false);

        List<Demo> records = mapper.selectPage(page, new LambdaQueryWrapper<Demo>()).getRecords();

        assertEquals((int) MybatisPlusConfig.MAX_QUERY_ROWS, records.size(),
            "不覆盖时会被全局 maxLimit 截断到 " + MybatisPlusConfig.MAX_QUERY_ROWS + " 行");
    }

    // ==================================================================
    // 3. 配置本身
    // ==================================================================

    @Test
    @DisplayName("配置类产出的是带 PaginationInnerInterceptor 的拦截器")
    void interceptorIsProperlyConfigured() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        assertNotNull(interceptor);
        List<?> interceptors = interceptor.getInterceptors();
        assertEquals(1, interceptors.size(), "应当只注册分页插件一个拦截器");
        assertTrue(interceptors.get(0) instanceof PaginationInnerInterceptor,
            "注册的必须是 PaginationInnerInterceptor —— "
                + "别的拦截器不会下推 LIMIT");
        assertEquals(DbType.MYSQL,
            ((PaginationInnerInterceptor) interceptors.get(0)).getDbType(),
            "方言必须是 MYSQL");
        assertEquals(MybatisPlusConfig.MAX_QUERY_ROWS,
            ((PaginationInnerInterceptor) interceptors.get(0)).getMaxLimit().longValue());
    }

    @Test
    @DisplayName("overflow=false：页码越界返回空而不是回退首页")
    void overflowIsDisabled() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        PaginationInnerInterceptor pagination =
            (PaginationInnerInterceptor) interceptor.getInterceptors().get(0);

        assertTrue(!pagination.isOverflow(),
            "overflow 必须是 false —— 开启后翻到不存在的页会回退到第一页，"
                + "用户完全意识不到自己看的是错的页");
    }

    // ==================================================================
    // 4. 分页参数夹取
    // ==================================================================

    @Test
    @DisplayName("page_size 被夹在 1~100")
    void pageSizeIsClamped() {
        assertEquals(100, PageParams.clampSize(1000));
        assertEquals(100, PageParams.clampSize(100));
        assertEquals(20, PageParams.clampSize(20));
        assertEquals(1, PageParams.clampSize(0));
        assertEquals(1, PageParams.clampSize(-5));
    }

    @Test
    @DisplayName("page 被夹到 >= 1（page=0 会让 OFFSET 变成负数）")
    void pageIsClamped() {
        assertEquals(1, PageParams.clampPage(0));
        assertEquals(1, PageParams.clampPage(-5));
        assertEquals(1, PageParams.clampPage(1));
        assertEquals(7, PageParams.clampPage(7));
    }
}
