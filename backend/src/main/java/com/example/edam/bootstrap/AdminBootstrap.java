package com.example.edam.bootstrap;

import com.example.edam.model.SysRole;
import com.example.edam.model.SysUser;
import com.example.edam.model.SysUserRole;
import com.example.edam.repository.SysRoleRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.SysUserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 系统启动自检（v3.2 V-1 RBAC）：
 * 1. 确保存在默认 admin 账号（<b>仅 dev / 未显式配置初始密码时</b>）
 * 2. 给 admin 绑定 admin + employee 双角色（默认 V3 迁移已写 employee 给所有用户，
 *    这里再加 admin）
 *
 * 默认账号（仅当 {@code edam.bootstrap.admin-password} 有值，或 dev profile 且
 * {@code edam.bootstrap.create-default-admin=true} 时创建）：
 *   工号: E000001
 *   密码: 见配置
 *
 * <p><b>安全说明</b>：原实现硬编码 {@code admin123} 且无任何 profile 开关，
 * production profile 下同样会跑 —— 等保测评里这是"默认弱口令"的高危项。
 * 现在改为：密码来自配置，默认<b>为空即不创建</b>，并在启动日志里给出
 * 明确的操作指引。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AdminBootstrap implements CommandLineRunner {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_EMPLOYEE_NO = "E000001";
    /** 仅 dev profile 使用的默认密码 */
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 初始 admin 密码；为空则不创建默认 admin */
    @org.springframework.beans.factory.annotation.Value("${edam.bootstrap.admin-password:}")
    private String adminPassword;

    /** 初始管理员归属部门 id；留空则自动取 sys_dept 第一条 */
    @org.springframework.beans.factory.annotation.Value("${edam.bootstrap.admin-dept-id:}")
    private String adminDeptIdRaw;

    /** 是否强制创建默认 admin（dev profile 场景可用） */
    @org.springframework.beans.factory.annotation.Value("${edam.bootstrap.create-default-admin:false}")
    private boolean createDefaultAdmin;

    /**
     * 当前激活的 Spring profile。
     *
     * <p>用于区分「本地开发」与「生产」：
     * dev 下允许用默认密码 admin123 快速起步；
     * production 下**绝不**创建带默认密码的账号。
     */
    @org.springframework.beans.factory.annotation.Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private Long adminDeptId;

    private boolean isDevProfile() {
        return activeProfile != null && (activeProfile.contains("dev") || activeProfile.isEmpty());
    }

    /**
     * 自举状态（供健康端点暴露）。
     *
     * <p>为什么要暴露出来：自举失败被降级成「只记日志、不阻断启动」之后，
     * 症状是**服务正常但登不进去**（前端只显示"工号或密码错误"，
     * 数据库里 sys_user 一条都没有），排查时完全没有线索。
     * 把它放进 /health/ready，运维一眼就能看到"管理员账号没建成"。
     */
    private static volatile String bootstrapStatus = "not-run";
    private static volatile String bootstrapError = null;

    public static String getBootstrapStatus() {
        return bootstrapStatus;
    }

    public static String getBootstrapError() {
        return bootstrapError;
    }

    @Override
    public void run(String... args) {
        try {
            if (adminDeptIdRaw != null && !adminDeptIdRaw.isBlank()) {
                try {
                    adminDeptId = Long.valueOf(adminDeptIdRaw.trim());
                } catch (NumberFormatException e) {
                    log.warn("edam.bootstrap.admin-dept-id 不是合法数字，已忽略: {}", adminDeptIdRaw);
                }
            }
            bootstrap();
            bootstrapStatus = "ok";
            bootstrapError = null;
        } catch (Exception e) {
            // 自举失败是**运维问题**，不该让整个后端起不来 ——
            // 之前这里抛异常会直接终止应用（进程 exit 1），
            // 管理员还得先想办法把服务弄起来才能进管理界面建账号。
            // 这里降级为 error 日志，服务照常启动，运维再手工建账号。
            //
            // 但降级**不能等于静默**：状态写进 /health/ready，
            // 且日志给出可操作的排查指引（见下方提示）。
            bootstrapStatus = "failed";
            bootstrapError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("admin_bootstrap_failed —— 初始管理员账号未创建。"
                + "登录会失败（前端只显示\"工号或密码错误\"，但 sys_user 表是空的）。"
                + "排查顺序："
                + "1) 确认迁移已全部应用：SELECT version, success FROM flyway_schema_history;"
                + "   特别确认 V20260830_5000（dept_id 改可空）已执行 —— "
                + "sys_user.dept_id 若是 NOT NULL 无默认值，插入会报 "
                + "\"Field 'dept_id' doesn't have a default value\"；"
                + "2) 确认启动的是 dev profile（生产 profile 不配 "
                + "edam.bootstrap.admin-password 就不建账号）；"
                + "3) 用 scripts/dev-check-admin.sh 一键查全。", e);
        }
    }

    /**
     * 解析初始管理员的部门 id。
     *
     * <p>顺序：显式配置 → sys_dept 第一条 → null。
     */
    private Long resolveDeptId() {
        if (adminDeptId != null) {
            return adminDeptId;
        }
        try {
            List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM sys_dept ORDER BY id LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"));
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            // sys_dept 表不存在 / 没数据 → 留 null（该列可空时没问题）
            log.warn("admin_dept_resolve_failed, fallback_to_null: {}", e.getMessage());
            return null;
        }
    }

    private void bootstrap() {
        SysUser admin = userRepository.findByEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
        if (admin == null) {
            // dev profile：没配密码就用 admin123，方便本地起服务
            // （生产 profile 走下面的分支 —— 不配就一个账号都不建）
            if (isDevProfile() && (adminPassword == null || adminPassword.isBlank())) {
                adminPassword = DEFAULT_ADMIN_PASSWORD;
                createDefaultAdmin = true;
                log.warn("dev profile：使用默认 admin 密码创建管理员账号（生产环境不会走到这里）");
            }
            if (adminPassword == null || adminPassword.isBlank()) {
                // 没有配置初始密码 → 不创建任何默认账号。
                // 生产环境必须通过运维通道手工建管理员账号。
                log.warn("========================================================");
                log.warn("EDAM: 未找到管理员账号 (employee_no={})，且未配置 "
                    + "edam.bootstrap.admin-password —— 不创建任何默认账号。", DEFAULT_ADMIN_EMPLOYEE_NO);
                log.warn("请通过管理接口或 SQL 手工创建管理员，或在部署时注入：");
                log.warn("  -Dedam.bootstrap.admin-password=<强密码>");
                log.warn("========================================================");
                return;
            }
            if (!createDefaultAdmin) {
                log.warn("edam.bootstrap.admin-password 已配置，但 edam.bootstrap.create-default-admin"
                    + " 未开启 —— 不创建默认管理员账号。");
                return;
            }
            // 创建默认 admin
            admin = new SysUser();
            admin.setUsername(DEFAULT_ADMIN_USERNAME);
            admin.setEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setRealName("系统管理员");
            admin.setEmail("admin@example.com");
            // 部门归属：
            //   - 优先用 edam.bootstrap.admin-dept-id 指定的部门
            //   - 否则取 sys_dept 里第一个（"总公司"）
            //   - 都没有就留 null
            //
            // 为什么要兜底：sys_user.dept_id 曾经是 NOT NULL 且无默认值，
            // 而初始管理员天然没有"部门归属" → 插入报
            // "Field 'dept_id' doesn't have a default value"，
            // 表现为**服务正常但登不进去**（用户表空的）。
            // V20260830_5000 已把该列改为可空，但万一那条迁移没跑
            // （或者将来又有人加必填列），这里兜底选一个部门，
            // 账号照样能建出来。
            admin.setDeptId(resolveDeptId());
            admin.setStatus(1);
            admin.setMfaEnabled(0);
            admin.setFailedLoginCount(0);
            admin.setMustChangePassword(true);
            admin.setPasswordChangedAt(OffsetDateTime.now());
            userRepository.insert(admin);
            log.warn("========================================================");
            log.warn("EDAM default admin user created: employee_no={}", DEFAULT_ADMIN_EMPLOYEE_NO);
            if (isDevProfile()) {
                log.warn("  dev 默认密码: {}", DEFAULT_ADMIN_PASSWORD);
            }
            log.warn("  ⚠️  MUST change password after first login!");
            log.warn("========================================================");
        } else {
            log.info("admin user already exists (id={})", admin.getId());
        }

        // 强制给 admin 账号绑 admin 角色（幂等：V3 迁移会先给 employee，
        // 这里再补 admin；用 ON DUPLICATE KEY 不重复插入）
        SysRole adminRole = roleRepository.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysRole>()
                .eq("code", "admin")
        ).stream().findFirst().orElse(null);
        if (adminRole == null) {
            log.warn("admin role not found in DB (V3 migration may not have run yet), skip role assignment");
            return;
        }

        // 检查是否已有 admin 角色绑定
        List<Long> existingRoles = userRoleRepository.findRoleIdsByUserId(admin.getId());
        if (!existingRoles.contains(adminRole.getId())) {
            SysUserRole sur = new SysUserRole();
            sur.setUserId(admin.getId());
            sur.setRoleId(adminRole.getId());
            sur.setGrantedBy(admin.getId());  // 系统自举
            userRoleRepository.insert(sur);
            log.info("admin user (id={}) bound to role 'admin' (id={})", admin.getId(), adminRole.getId());
        } else {
            log.info("admin user (id={}) already has 'admin' role", admin.getId());
        }
    }
}
