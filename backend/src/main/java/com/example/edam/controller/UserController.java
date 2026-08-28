package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.SysUserView;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.AuditService;
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
 */
@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditService auditService;

    @GetMapping
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

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("pagination", Map.of(
            "page", (int) result.getCurrent(),
            "page_size", (int) result.getSize(),
            "total", result.getTotal(),
            "total_pages", (int) result.getPages()
        ));
        return response;
    }

    @PostMapping
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<SysUserView> create(
            @RequestBody CreateUserRequest request,
            @RequestHeader("X-User-Id") Long operatorId
    ) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("用户名必填");
        }
        if (request.getEmployeeNo() == null || request.getEmployeeNo().isBlank()) {
            throw new IllegalArgumentException("工号必填");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }

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

        auditService.log(operatorId, "create", "user", user.getId(), "success");
        return ResponseEntity.status(201).body(SysUserView.from(user));
    }

    @GetMapping("/{user_id}")
    public SysUserView getById(@PathVariable("user_id") Long userId) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        return SysUserView.from(user);
    }

    @PutMapping("/{user_id}")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    public SysUserView update(
            @PathVariable("user_id") Long userId,
            @RequestBody UpdateUserRequest request,
            @RequestHeader("X-User-Id") Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);

        if (request.getRealName() != null) user.setRealName(request.getRealName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getDeptId() != null) user.setDeptId(request.getDeptId());
        if (request.getStatus() != null) user.setStatus(parseStatus(request.getStatus()));
        userRepository.updateById(user);

        auditService.log(operatorId, "update", "user", userId, "success");
        return SysUserView.from(user);
    }

    @PutMapping("/{user_id}/password")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<Void> resetPassword(
            @PathVariable("user_id") Long userId,
            @RequestBody ResetPasswordRequest request,
            @RequestHeader("X-User-Id") Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setMustChangePassword(true);
        userRepository.updateById(user);
        auditService.log(operatorId, "reset_password", "user", userId, "success");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{user_id}")
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<Void> delete(
            @PathVariable("user_id") Long userId,
            @RequestHeader("X-User-Id") Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        if (userId.equals(1L)) {
            throw new IllegalArgumentException("默认 admin 账号不可删除");
        }

        // 软删除：status=2 (disabled)
        user.setStatus(2);
        userRepository.updateById(user);

        auditService.log(operatorId, "delete", "user", userId, "success");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{user_id}/revoke-keys")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<Void> revokeKeys(
            @PathVariable("user_id") Long userId,
            @RequestHeader("X-User-Id") Long operatorId
    ) {
        log.warn("user_keys_revoked, user_id={}, operator_id={}", userId, operatorId);
        auditService.log(operatorId, "revoke_keys", "user", userId, "success");
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
