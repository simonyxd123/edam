package com.example.edam.service;

import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysPermission;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysPermissionRepository;
import com.example.edam.repository.SysRolePermissionRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.SysUserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限服务（v3.2 V-1 RBAC）
 *
 * 核心方法：
 * - getUserPermissionCodes(userId): 聚合用户所有角色的权限 code 集合
 * - hasPermission(userId, code): 单一权限检查
 * - getCurrentUserRoleCodes(userId): 用户的角色 code 集合（含 admin 短路）
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermRepository;
    private final SysPermissionRepository permissionRepository;

    /**
     * 用户的全部权限 code 集合（去重）。
     * 如果用户属于 admin 角色 → 返回 ["*:*"]，调用方需短路处理。
     * expires_at < now 的角色会被过滤。
     */
    @Transactional(readOnly = true)
    public Set<String> getUserPermissionCodes(Long userId) {
        if (userId == null) return Set.of();
        SysUser user = userRepository.selectById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在");

        List<Long> roleIds = userRoleRepository.findRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) return Set.of();

        // admin 短路：只要有 admin 角色就给 *:*
        Set<String> roleCodes = userRoleRepository.findRolesByUserId(userId).stream()
            .map(r -> r.getCode())
            .collect(Collectors.toSet());
        if (roleCodes.contains("admin")) {
            return Set.of("*:*");
        }

        Set<String> codes = new HashSet<>();
        for (Long rid : roleIds) {
            List<Long> permIds = rolePermRepository.findPermissionIdsByRoleId(rid);
            if (permIds.isEmpty()) continue;
            List<SysPermission> perms = permissionRepository.selectBatchIds(permIds);
            perms.forEach(p -> codes.add(p.getCode()));
        }
        return codes;
    }

    /**
     * 单一权限检查（含通配）：
     * - 拥有 *:* → 所有权限通过
     * - 拥有 *:<action> → 该 action 所有资源通过
     * - 否则查 code 精确匹配
     */
    public boolean hasPermission(Long userId, String code) {
        if (userId == null || code == null) return false;
        Set<String> codes = getUserPermissionCodes(userId);
        if (codes.contains("*:*")) return true;
        int colon = code.indexOf(':');
        if (colon > 0 && codes.contains("*:" + code.substring(colon + 1))) return true;
        return codes.contains(code);
    }

    /**
     * 当前用户所有角色 code（含 admin）
     */
    @Transactional(readOnly = true)
    public Set<String> getCurrentUserRoleCodes(Long userId) {
        if (userId == null) return Set.of();
        return userRoleRepository.findRolesByUserId(userId).stream()
            .map(r -> r.getCode())
            .collect(Collectors.toSet());
    }
}
