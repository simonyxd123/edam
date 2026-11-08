package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.dto.SysUserView;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理 Controller（v3.2 V-1 RBAC Phase 2）
 * 输出 snake_case JSON（用 SysUserView DTO 转换），前端 UserDoc 接口字段对齐
 * 所有写接口 @PreAuthorize('user:manage') 保护
 *
 * <p><b>读接口同样有门（2026-11-07 补）</b>：原先只给写接口设门，
 * {@code GET /users} 与 {@code GET /users/{id}} 对**所有登录用户**敞开 ——
 * 任何普通员工都能拉出全公司员工名册（工号 / 姓名 / 邮箱 / 部门 /
 * MFA 开关 / 登录失败次数 / 最后登录时间与 IP）。前端「用户管理」Tab 在
 * 「角色权限」页里、只对 admin 可见，但那只是菜单过滤，API 本身没人管。
 * 现在按权限目录里已有的 {@code user:read} 设门 ——
 * admin 与 dept_manager 都持有该权限（见 rbac_seed 迁移），不受影响。
 */
@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.example.edam.service.PasswordService passwordService;
    private final com.example.edam.security.PasswordPolicy passwordPolicy;
    private final com.example.edam.service.AuthService authService;

    @GetMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:read')")
    @AuditOp(op = AuditOpType.LIST, resourceType = "user")
    public Map<String, Object> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int page_size,
        @RequestParam(required = false) Long dept_id,
        @RequestParam(required = false) String status
    ) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (dept_id != null) wrapper.eq(SysUser::getDeptId, dept_id);
        if (status != null) {
            wrapper.eq(SysUser::getStatus, parseStatus(status));
        }
        wrapper.orderByDesc(SysUser::getCreatedAt);
        Page<SysUser> result = userRepository.selectPage(new Page<>(page, page_size), wrapper);

        // 转 SysUserView DTO（snake_case + 脱敏 password_hash）
        List<SysUserView> items = result.getRecords().stream()
            .map(SysUserView::from)
            .collect(Collectors.toList());

        // 统一分页壳（见 dto/PaginationResponses）
        return PaginationResponses.of(result, SysUserView::from);
    }

    @PostMapping
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    @AuditOp(op = AuditOpType.CREATE, resourceType = "user",
        detail = "username=#request.username, employee_no=#request.employeeNo")
    public ResponseEntity<SysUserView> create(
        @RequestBody CreateUserRequest request,
        @CurrentUserId Long operatorId
    ) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("用户名必填");
        }
        if (request.getEmployeeNo() == null || request.getEmployeeNo().isBlank()) {
            throw new IllegalArgumentException("工号必填");
        }
        // 走统一的密码策略（长度 / 复杂度至少 3 类 / 弱口令黑名单）。
        // 原先只查 length < 6 → 管理员可以给用户设 "123456"。
        passwordPolicy.validateOrThrow(request.getPassword());


        // 唯一性检查
        if (userRepository.selectList(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmployeeNo, request.getEmployeeNo())
        ).size() > 0) {
            throw new IllegalArgumentException("工号已存在: " + request.getEmployeeNo());
        }
        if (userRepository.selectList(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername())
        ).size() > 0) {
            throw new IllegalArgumentException("用户名已存在: " + request.getUsername());
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmployeeNo(request.getEmployeeNo());
        user.setRealName(request.getRealName());
        user.setEmail(request.getEmail());
        user.setDeptId(request.getDeptId());
        user.setStatus(1);  // active
        user.setMfaEnabled(request.getMfaEnabled() != null ? request.getMfaEnabled() : 0);
        user.setFailedLoginCount(0);
        user.setMustChangePassword(true);  // 强制首次登录改密
        userRepository.insert(user);

        return ResponseEntity.status(201).body(SysUserView.from(user));
    }

    @GetMapping("/{user_id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:read')")
    @AuditOp(op = AuditOpType.DETAIL, resourceType = "user", resourceId = "#userId")
    public SysUserView getById(@PathVariable("user_id") Long userId) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        return SysUserView.from(user);
    }

    @PutMapping("/{user_id}")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "user", resourceId = "#userId",
        detail = "real_name=#request.realName, email=#request.email, status=#request.status")
    public SysUserView update(
        @PathVariable("user_id") Long userId,
        @RequestBody UpdateUserRequest request,
        @CurrentUserId Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);

        if (request.getRealName() != null) user.setRealName(request.getRealName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getDeptId() != null) user.setDeptId(request.getDeptId());
        if (request.getStatus() != null) user.setStatus(parseStatus(request.getStatus()));
        userRepository.updateById(user);

        return SysUserView.from(user);
    }

    @PutMapping("/{user_id}/password")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "user", resourceId = "#userId",
        detail = "action=reset_password", hideParams = true)
    public ResponseEntity<Map<String, Object>> resetPassword(
        @PathVariable("user_id") Long userId,
        @RequestBody ResetPasswordRequest request,
        @CurrentUserId Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        // 走 PasswordService.resetPassword：
        //   - 统一密码策略（长度 / 复杂度 / 弱口令黑名单）
        //   - 写 sys_password_history（历史密码不可重用 —— 等保要求）
        //   - 置 must_change_password（强制用户下次登录改密）
        // 原来这里只查 length < 6，且从不写历史 → 历史密码表永远是空的
        String tempPassword = passwordService.resetPassword(userId, operatorId);
        // 注意：临时密码**不写进审计 detail** —— 审计表是可被审计员读到的，
        // 密码落库等于二次泄露。原始密码通过响应体返回给管理员（一次性）。
        log.info("user_password_reset, user_id={}, operator_id={}, temp_password_issued=true",
            userId, operatorId);
        // Map.of 的 value 类型要一致（String + Boolean 混用会推断失败）
        return ResponseEntity.ok(Map.<String, Object>of(
            "temp_password", tempPassword,
            "must_change_password", true));
    }

    @DeleteMapping("/{user_id}")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    @AuditOp(op = AuditOpType.DELETE, resourceType = "user", resourceId = "#userId")
    public ResponseEntity<Void> delete(
        @PathVariable("user_id") Long userId,
        @CurrentUserId Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        if (userId.equals(1L)) {
            throw new IllegalArgumentException("默认 admin 账号不可删除");
        }

        // 软删除：status=2 (disabled)
        user.setStatus(2);
        userRepository.updateById(user);

        return ResponseEntity.noContent().build();
    }

    /**
     * 紧急吊销该用户的全部密钥（离职 / 违规）。
     *
     * <p><b>原实现是空壳</b>（2026-11-07 修）：只打了一条日志就返回 204，
     * token 一个都没作废 —— 管理员点「紧急吊销」看到成功提示，
     * 但被吊销的人照样能用 refresh_token 续期 7 天。
     *
     * <p>现在真的会吊销（见 {@code AuthService#revokeAllForUser}）：
     * 该用户全部 refresh_token 立即作废，他再也换不到新的 access_token。
     * 已经签发出去的那张 access_token 是无状态 JWT，会活到自己的 TTL
     * （默认 10 分钟）为止 —— 这是架构事实，手册里按这个口径写。
     */
    @PostMapping("/{user_id}/revoke-keys")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    @AuditOp(op = AuditOpType.UPDATE, resourceType = "user", resourceId = "#userId",
        detail = "action=revoke_keys")
    public ResponseEntity<Void> revokeKeys(
        @PathVariable("user_id") Long userId,
        @CurrentUserId Long operatorId
    ) {
        authService.revokeAllForUser(userId, "紧急吊销 by operator " + operatorId);
        return ResponseEntity.noContent().build();
    }

    private Integer parseStatus(String s) {
        return switch (s) {
            case "active" -> 1;
            case "disabled" -> 2;
            case "locked" -> 3;
            default -> 1;
        };
    }

    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;

        @JsonProperty("employee_no")
        private String employeeNo;

        @JsonProperty("real_name")
        private String realName;

        private String email;

        @JsonProperty("dept_id")
        private Long deptId;

        @JsonProperty("mfa_enabled")
        private Integer mfaEnabled;
    }

    @Data
    public static class UpdateUserRequest {
        @JsonProperty("real_name")
        private String realName;

        private String email;

        @JsonProperty("dept_id")
        private Long deptId;

        private String status;
    }

    @Data
    public static class ResetPasswordRequest {
        private String password;
    }
}
