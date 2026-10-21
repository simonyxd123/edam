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

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    /** 初始 admin 密码；为空则不创建默认 admin */
    @org.springframework.beans.factory.annotation.Value("${edam.bootstrap.admin-password:}")
    private String adminPassword;

    /** 是否强制创建默认 admin（dev profile 场景可用） */
    @org.springframework.beans.factory.annotation.Value("${edam.bootstrap.create-default-admin:false}")
    private boolean createDefaultAdmin;

    @Override
    public void run(String... args) {
        SysUser admin = userRepository.findByEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
        if (admin == null) {
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
            admin.setStatus(1);
            admin.setMfaEnabled(0);
            admin.setFailedLoginCount(0);
            admin.setMustChangePassword(true);
            admin.setPasswordChangedAt(OffsetDateTime.now());
            userRepository.insert(admin);
            log.warn("========================================================");
            log.warn("EDAM default admin user created: employee_no={}", DEFAULT_ADMIN_EMPLOYEE_NO);
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
