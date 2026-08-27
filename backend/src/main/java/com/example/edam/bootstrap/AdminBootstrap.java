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
 * 1. 确保存在默认 admin 账号
 * 2. 给 admin 绑定 admin + employee 双角色（默认 V3 迁移已写 employee 给所有用户，
 *    这里再加 admin）
 *
 * 默认账号：
 *   工号: admin
 *   密码: admin123
 *   强制首次登录后修改密码（must_change_password=1）
 *
 * 生产环境首次登录后必须立即改密码 + 关闭/禁用此默认账号。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AdminBootstrap implements CommandLineRunner {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_EMPLOYEE_NO = "E000001";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        SysUser admin = userRepository.findByEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
        if (admin == null) {
            // 第一次启动：创建默认 admin
            admin = new SysUser();
            admin.setUsername(DEFAULT_ADMIN_USERNAME);
            admin.setEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
            admin.setPasswordHash(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
            admin.setRealName("系统管理员");
            admin.setEmail("admin@example.com");
            admin.setStatus(1);
            admin.setMfaEnabled(0);
            admin.setFailedLoginCount(0);
            admin.setMustChangePassword(true);
            admin.setPasswordChangedAt(OffsetDateTime.now());
            userRepository.insert(admin);
            log.warn("========================================================");
            log.warn("EDAM default admin user created:");
            log.warn("  employee_no: {}", DEFAULT_ADMIN_EMPLOYEE_NO);
            log.warn("  password:    {}", DEFAULT_ADMIN_PASSWORD);
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
