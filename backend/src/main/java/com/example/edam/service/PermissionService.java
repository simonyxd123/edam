package com.example.edam.service;

import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysPermission;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysPermissionRepository;
import com.example.edam.repository.SysRolePermissionRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.SysUserRoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *
 * 注意：admin 用户返回「全部 45 个权限码」而非通配符 "*:*"。
 * 因为 Spring Security 的 hasAuthority() 是精确匹配字符串，
 * "*:*" 不会匹配 "role:read"，所以 admin 必须实际拥有全部权限码
 * 才能通过所有 @PreAuthorize 校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysUserRepository userRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysRolePermissionRepository rolePermRepository;
    private final SysPermissionRepository permissionRepository;

    /** 全部权限 code（启动时一次性加载，用于 admin 短路返回全部权限） */
    private Set<String> allPermissionCodes = Set.of();

    @PostConstruct
    public void init() {
        allPermissionCodes = permissionRepository.selectList(null).stream()
            .map(SysPermission::getCode)
            .collect(Collectors.toSet());
        log.info("PermissionService init: loaded {} permission codes", allPermissionCodes.size());
    }

    /**
     * 用户的全部权限 code 集合（去重）。
     * 如果用户属于 admin 角色 → 返回「全部 45 个权限码」（不是 "*:*" 通配符）。
     * expires_at < now 的角色会被过滤。
     */
    @Transactional(readOnly = true)
    public Set<String> getUserPermissionCodes(Long userId) {
        if (userId == null) return Set.of();
        SysUser user = userRepository.selectById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在");

        List<Long> roleIds = userRoleRepository.findRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) return Set.of();

        // admin 短路：返回全部权限码（让 hasAuthority 精确匹配全部通过）
        Set<String> roleCodes = userRoleRepository.findRolesByUserId(userId).stream()
            .map(r -> r.getCode())
            .collect(Collectors.toSet());
        if (roleCodes.contains("admin")) {
            return new HashSet<>(allPermissionCodes);
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
