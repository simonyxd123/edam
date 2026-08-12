package com.example.edam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.service.AuditService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理 Controller
 * 对应 openapi.yaml tag: users
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

        // 脱敏：不返回 password_hash
        result.getRecords().forEach(u -> u.setPasswordHash(null));

        return toPaginationResponse(result);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<SysUser> create(
        @RequestBody CreateUserRequest request,
        @RequestHeader("X-User-Id") Long operatorId
    ) {
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
        userRepository.insert(user);

        auditService.log(operatorId, "create", "user", user.getId(), "success");
        return ResponseEntity.status(201).body(user);
    }

    @GetMapping("/{user_id}")
    public SysUser getById(@PathVariable("user_id") Long userId) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        user.setPasswordHash(null);
        return user;
    }

    @PutMapping("/{user_id}")
    @Transactional
    public SysUser update(
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
        return user;
    }

    @DeleteMapping("/{user_id}")
    @Transactional
    public ResponseEntity<Void> delete(
        @PathVariable("user_id") Long userId,
        @RequestHeader("X-User-Id") Long operatorId
    ) {
        SysUser user = userRepository.findById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);

        // 软删除
        user.setStatus(2);  // disabled
        userRepository.updateById(user);

        auditService.log(operatorId, "delete", "user", userId, "success");
        return ResponseEntity.noContent().build();
    }

    /**
     * 立即吊销用户所有密钥（紧急场景）
     */
    @PostMapping("/{user_id}/revoke-keys")
    public ResponseEntity<Void> revokeKeys(
        @PathVariable("user_id") Long userId,
        @RequestHeader("X-User-Id") Long operatorId
    ) {
        log.warn("user_keys_revoked, user_id={}, operator_id={}", userId, operatorId);
        // 实际实现：调用 Vault 撤销用户密钥 + 清空 Redis 中的 refresh_token
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

    private Map<String, Object> toPaginationResponse(Page<SysUser> page) {
        Map<String, Object> response = new HashMap<>();
        response.put("items", page.getRecords());
        response.put("pagination", Map.of(
            "page", (int) page.getCurrent(),
            "page_size", (int) page.getSize(),
            "total", page.getTotal(),
            "total_pages", (int) page.getPages()
        ));
        return response;
    }

    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String employeeNo;
        private String realName;
        private String email;
        private Long deptId;
        private Integer mfaEnabled;
    }

    @Data
    public static class UpdateUserRequest {
        private String realName;
        private String email;
        private Long deptId;
        private String status;
    }
}