package com.example.edam.service;

import com.example.edam.model.SysRole;
import com.example.edam.model.SysUserRole;
import com.example.edam.repository.SysRoleRepository;
import com.example.edam.repository.SysUserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * - grantRoleByCode(userId, roleCode): 系统自动授予（不覆盖已有角色）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleService {

    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
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
     * 授予一个角色（按 role code）—— 用于 JIT 开通等**系统自动流程**。
     *
     * <p>与 {@link #assignRoles} 的区别：
     * <ul>
     *   <li>**不覆盖**已有角色 —— 只在缺失时补一个</li>
     *   <li>不需要 operatorId —— 自动开通时没有操作人，{@code granted_by} 留空</li>
     * </ul>
     *
     * <p>存在的意义：SSO / WebAuthn 等外部身份源登录的用户必须**在 RBAC 表里
     * 真的有角色**，否则签发 JWT 时只能拿到兜底的 employee 角色。
     *
     * @return 是否实际写入（角色不存在或已拥有时返回 false）
     */
    @Transactional
    public boolean grantRoleByCode(Long userId, String roleCode) {
        if (userId == null || roleCode == null || roleCode.isBlank()) {
            return false;
        }
        SysRole role = roleRepository.findByCode(roleCode);
        if (role == null) {
            log.warn("授予角色失败：角色不存在 role_code={}", roleCode);
            return false;
        }
        if (userRoleRepository.findRoleIdsByUserId(userId).contains(role.getId())) {
            return false;
        }
        SysUserRole ur = new SysUserRole();
        ur.setUserId(userId);
        ur.setRoleId(role.getId());
        ur.setGrantedBy(null);   // 系统自动授予，无操作人
        userRoleRepository.insert(ur);
        log.info("系统授予角色: user_id={}, role_code={}", userId, roleCode);
        return true;
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
                // grantedAt 由 MyMetaObjectHandler 自动填（UTC），不用手动设
                userRoleRepository.insert(ur);
                added++;
            }
        }
        auditService.log(operatorId, "assign_roles", "user", userId, "success",
            "role_count=" + added, null, null);
        return added;
    }
}
