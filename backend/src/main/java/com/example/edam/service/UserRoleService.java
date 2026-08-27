package com.example.edam.service;

import com.example.edam.model.SysRole;
import com.example.edam.model.SysUserRole;
import com.example.edam.repository.SysUserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户-角色 服务（v3.2 V-1 RBAC）
 *
 * 业务：
 * - getRoleCodes(userId): 角色 code 列表（用于 JWT claims）
 * - assignRoles(userId, roleIds, operatorId): 全量替换 + 审计
 */
@Service
@RequiredArgsConstructor
public class UserRoleService {

    private final SysUserRoleRepository userRoleRepository;
    private final AuditService auditService;

    /**
     * 用户所有角色 code 列表（去重，过滤软删）
     */
    public List<String> getRoleCodes(Long userId) {
        if (userId == null) return List.of();
        return userRoleRepository.findRolesByUserId(userId).stream()
            .map(SysRole::getCode)
            .filter(c -> c != null)
            .collect(Collectors.toList());
    }

    /**
     * 分配角色（全量替换）
     */
    @Transactional
    public int assignRoles(Long userId, List<Long> roleIds, Long operatorId) {
        userRoleRepository.deleteByUserId(userId);
        int added = 0;
        if (roleIds != null) {
            for (Long rid : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(rid);
                ur.setGrantedBy(operatorId);
                userRoleRepository.insert(ur);
                added++;
            }
        }
        auditService.log(operatorId, "assign_roles", "user", userId, "success",
            "role_count=" + added, null, null);
        return added;
    }
}
