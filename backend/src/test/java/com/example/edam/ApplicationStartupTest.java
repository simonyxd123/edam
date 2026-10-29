package com.example.edam;

import com.example.edam.model.SysUser;
import com.example.edam.model.SysRolePermission;
import com.example.edam.model.SysUserRole;
import com.example.edam.model.WebhookDelivery;
import com.example.edam.model.OperationLog;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.repository.SysRolePermissionRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.SysUserRoleRepository;
import com.example.edam.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Autowired
    private SysUserRoleRepository userRoleRepository;

    @Autowired
    private SysRolePermissionRepository rolePermRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private OperationLogRepository operationLogRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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

    /**
     * 初始管理员必须真的被建出来。
     *
     * <p>这是**真 MySQL** 上的端到端验证 —— 之前 AdminBootstrap 的插入
     * 失败被静默吞掉，症状是"服务正常但登不进去"（前端只显示
     * "工号或密码错误"，库里 sys_user 一条都没有）。本用例在 CI 上
     * 跑真迁移 + 真插入，任何一步断了都会红。
     */
    @Test
    @DisplayName("初始管理员 E000001 已被建出，且密码可用")
    void initialAdminAccountIsCreated() {
        SysUser admin = userRepository.findByEmployeeNo("E000001");
        assertNotNull(admin,
            "初始管理员未创建 —— 查 backend/backend.log 里的 admin_bootstrap_failed");
        assertNotNull(admin.getPasswordHash(), "管理员必须有密码 hash");
        assertTrue(passwordEncoder.matches("Test@123456", admin.getPasswordHash()),
            "管理员密码必须能通过校验（测试配置里用的是 Test@123456）");
    }

    /**
     * 自动填充的列必须真的被填上 —— 在<b>真 MySQL</b> 上验证。
     *
     * <p>这条直接复现过一次服务器启动失败：
     * {@code Column 'granted_at' cannot be null}。
     *
     * <p>根因：{@code MyMetaObjectHandler} 走 MP 的
     * {@code strictInsertFill}，而它要求"属性名 + 属性类型完全相等"才填充。
     * handler 里写死的是 {@code OffsetDateTime.class}，
     * 而 {@code SysUserRole.grantedAt} 是 {@code LocalDateTime}
     * ⇒ 填充被<b>静默跳过</b> ⇒ 插入 NULL ⇒ 撞 NOT NULL 约束
     * ⇒ {@code AdminBootstrap} 绑定 admin 角色时抛异常
     * ⇒ dev profile 下 fail fast ⇒ 整个后端起不来。
     *
     * <p>注意：MP 对标了 {@code fill} 的字段，生成 INSERT 语句时是
     * <b>无条件</b>带上该列的（{@code withInsertFill} 为真时不加 {@code <if>} 判空），
     * 所以 DB 侧的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 根本没机会生效 ——
     * 值只能由 handler 提供。
     */
    @Test
    @Transactional
    @DisplayName("标了 fill 的时间列在真 MySQL 上确实被填上（granted_at 这类 NOT NULL 列）")
    void filledTimestampColumnsAreNotNull() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(999001L);
        ur.setRoleId(999001L);
        ur.setGrantedBy(999001L);
        // 故意**不设** grantedAt —— 它必须由 MyMetaObjectHandler 自动填上。
        // 填不上就是插入 NULL，直接撞 sys_user_role.granted_at 的 NOT NULL 约束。

        userRoleRepository.insert(ur);

        assertNotNull(ur.getGrantedAt(),
            "granted_at 必须被自动填充 —— 填不上就是插入 NULL，"
            + "AdminBootstrap 绑 admin 角色时会撞 NOT NULL 约束导致后端起不来");

        SysUserRole saved = userRoleRepository.selectById(ur.getId());
        assertNotNull(saved, "插入后应能按主键查回");
        assertNotNull(saved.getGrantedAt(), "落库的 granted_at 不能是 NULL");
    }

    /**
     * 同一个根因还炸在另外两条插入路径上 —— 一并锁住。
     *
     * <p>它们和 {@code sys_user_role.granted_at} 是同一个毛病：
     * 字段类型是 {@code LocalDateTime} / {@code Instant}，而 handler 只填
     * {@code OffsetDateTime} ⇒ 静默跳过 ⇒ 插 NULL ⇒ 撞 NOT NULL。
     *
     * <ul>
     *   <li>{@code sys_role_permission.created_at}（LocalDateTime）
     *       —— 给角色分配权限时插</li>
     *   <li>{@code webhook_delivery.created_at}（Instant）
     *       —— 记录 webhook 投递时插</li>
     * </ul>
     *
     * <p>这两个功能此前只要被调用就必然抛
     * {@code DataIntegrityConstraintViolationException}，只是还没人点到。
     */
    @Test
    @Transactional
    @DisplayName("另外两条 NOT NULL 时间列的插入路径也能通（角色权限 / webhook 投递）")
    void otherNotNullTimestampColumnsAreFilled() {
        // 1) sys_role_permission.created_at（LocalDateTime）
        SysRolePermission srp = new SysRolePermission();
        srp.setRoleId(999002L);
        srp.setPermissionId(999002L);
        // 故意不设 createdAt

        rolePermRepository.insert(srp);

        assertNotNull(srp.getCreatedAt(),
            "sys_role_permission.created_at 必须被自动填充 —— 填不上则分配权限时必炸");
        assertNotNull(rolePermRepository.selectById(srp.getId()).getCreatedAt(),
            "落库的 sys_role_permission.created_at 不能是 NULL");

        // 2) webhook_delivery.created_at（Instant）
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookId(999003L);
        delivery.setEvent("video.view");
        delivery.setPayload("{}");
        delivery.setDeliveredAt(Instant.now());
        // 故意不设 createdAt

        deliveryRepository.insert(delivery);

        assertNotNull(delivery.getCreatedAt(),
            "webhook_delivery.created_at 必须被自动填充 —— 填不上则记录投递时必炸");
        assertNotNull(deliveryRepository.selectById(delivery.getId()).getCreatedAt(),
            "落库的 webhook_delivery.created_at 不能是 NULL");
    }

    /**
     * 审计日志的 {@code detail} 必须能存**人读字符串**。
     *
     * <p>这条直接复现过一次静默故障（2026-10-29）：服务器日志里一片
     * {@code audit_log_write_failed … Data truncation: Invalid JSON text
     * "Invalid value." at position 0 in value for column 'operation_log.detail'}。
     *
     * <p>根因：建表时 {@code detail} 定义成了 {@code JSON} 列，但代码写进去的
     * 是自由文本 —— {@code "employee_no=E000101"}、
     * {@code "ip=127.0.0.1, mime=application/pdf, watermark=on"}、
     * {@code "refresh_token cleared"}，没有一个是合法 JSON ⇒ MySQL 直接拒绝
     * ⇒ <b>凡是带 detail 的审计记录全部丢失</b>（登录、预览、下载……）。
     *
     * <p>为什么这在合规系统里格外严重：写入失败只体现在服务端日志的一行
     * ERROR 里，没人看；而审计表看起来"正常"（有零星几行没带 detail 的），
     * 更容易被当成"没发生什么"。
     *
     * <p>修复见 {@code V20261029_1000__operation_log_detail_to_text.sql}
     * （detail 改成 TEXT）。本用例在<b>真 MySQL</b> 上验证 ——
     * 类型对不对，只有真库能说了算。
     */
    @Test
    @Transactional
    @DisplayName("审计日志能写入人读字符串 detail（detail 不是 JSON 列）")
    void auditLogAcceptsHumanReadableDetail() {
        OperationLog entry = new OperationLog();
        entry.setUserId(999010L);
        entry.setOperationType("login");
        entry.setResourceType("auth");
        entry.setResult(1);
        // 故意写**不是合法 JSON** 的字符串 —— 调用点就是这么写的。
        // 如果 detail 还是 JSON 列，这行插入会被 MySQL 拒掉，
        // 审计记录静默丢失（用户永远看不到自己在系统里干了什么）。
        entry.setDetail("employee_no=E000101, reason=密码错误");

        operationLogRepository.insert(entry);

        OperationLog saved = operationLogRepository.selectById(entry.getId());
        assertNotNull(saved, "审计记录插入后应能查回");
        assertEquals("employee_no=E000101, reason=密码错误", saved.getDetail(),
            "detail 必须原样落库 —— 审计详情是人读文本，不是 JSON");
    }
}
