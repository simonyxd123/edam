package com.example.edam.bootstrap;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 系统启动自检：确保存在默认 admin 账号。
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
@Order(1)   // 早于其他业务启动
@RequiredArgsConstructor
public class AdminBootstrap implements CommandLineRunner {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_EMPLOYEE_NO = "E000001";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        SysUser existing = userRepository.findByEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
        if (existing != null) {
            log.info("admin user already exists (id={}), skip bootstrap", existing.getId());
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername(DEFAULT_ADMIN_USERNAME);
        admin.setEmployeeNo(DEFAULT_ADMIN_EMPLOYEE_NO);
        admin.setPasswordHash(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
        admin.setRealName("系统管理员");
        admin.setEmail("admin@example.com");
        admin.setStatus(1);                  // active
        admin.setMfaEnabled(0);
        admin.setFailedLoginCount(0);
        admin.setMustChangePassword(true);    // 强制首次登录改密码
        admin.setPasswordChangedAt(OffsetDateTime.now());
        userRepository.insert(admin);

        log.warn("========================================================");
        log.warn("EDAM default admin user created:");
        log.warn("  employee_no: {}", DEFAULT_ADMIN_EMPLOYEE_NO);
        log.warn("  password:    {}", DEFAULT_ADMIN_PASSWORD);
        log.warn("  ⚠️  MUST change password after first login!");
        log.warn("========================================================");
    }
}