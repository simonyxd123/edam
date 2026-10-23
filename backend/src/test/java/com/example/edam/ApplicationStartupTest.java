package com.example.edam;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用启动冒烟测试。
 *
 * <p><b>为什么需要它</b>：本仓库此前后端**一个测试类都没有**，
 * {@code mvn test} 一直是空跑（还带 {@code -DfailIfNoTests=false}
 * 明确允许零测试通过）。结果所有启动期问题 —— Bean 装配、Flyway 迁移、
 * model 与 schema 不匹配、{@code AdminBootstrap} 插入失败 ——
 * 全都是在服务器上一轮轮试出来的，而不是被 CI 拦住的。
 *
 * <p>这个测试一次性覆盖：
 * <ol>
 *   <li>整个 Spring 上下文能起来（所有 Bean 装配正确）</li>
 *   <li>Flyway 能把全套迁移在**真 MySQL** 上跑通
 *       （H2 的 MySQL 兼容模式跑不了这些方言，测了等于没测）</li>
 *   <li>{@code AdminBootstrap} 的插入路径可用
 *       —— 它是 CommandLineRunner，{@code @SpringBootTest} 会执行它</li>
 *   <li>MyBatis 的 model ↔ schema 映射正确：
 *       "只设部分字段" 的插入不会被 NOT NULL 约束拒绝</li>
 * </ol>
 *
 * <p>第 4 条直接复现过一次线上故障：
 * {@code Field 'dept_id' doesn't have a default value}
 * —— {@code AdminBootstrap} 建初始管理员时不设 dept_id，
 * 而该列曾是 NOT NULL 无默认值。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("应用启动冒烟")
class ApplicationStartupTest {

    @Autowired
    private SysUserRepository userRepository;

    @Test
    @Transactional
    @DisplayName("上下文能启动，且能插入只设部分字段的用户")
    void contextLoadsAndPartialInsertWorks() {
        // 1) 上下文起来了（能走到这里就说明 Bean 装配 + Flyway 都过了）

        // 2) 部分字段插入：故意**不设** deptId / realName / phone 等可选字段。
        //    这正是 AdminBootstrap 的插入形状 —— 如果 schema 把某个
        //    "model 说可空"的列定义成 NOT NULL 无默认值，这里就会报
        //    "Field 'x' doesn't have a default value"。
        SysUser u = new SysUser();
        u.setUsername("smoke_user");
        u.setPasswordHash("$2a$12$smoke");
        u.setEmployeeNo("E990001");
        u.setStatus(1);
        u.setMfaEnabled(0);
        u.setFailedLoginCount(0);
        u.setMustChangePassword(false);

        userRepository.insert(u);

        SysUser saved = userRepository.findByEmployeeNo("E990001");
        assertNotNull(saved, "插入后应能按工号查回");
        assertTrue(saved.getDeptId() == null, "未指定部门时 dept_id 应为 NULL 而不是插入失败");
    }

    @Test
    @DisplayName("Flyway 已把迁移应用到当前 schema")
    void flywayAppliedAllMigrations() {
        // Flyway 迁移失败时应用根本起不来；能走到这里说明都过了。
        // 这里再确认一下关键表存在（迁移漏建表的情况）
        assertNotNull(userRepository.selectCount(null),
            "sys_user 表应存在且可查询");
    }
}
