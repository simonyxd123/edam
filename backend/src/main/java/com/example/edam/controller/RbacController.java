package com.example.edam.controller;

import com.example.edam.dto.PermissionDTO;
import com.example.edam.dto.RoleDTO;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.SysPermission;
import com.example.edam.model.SysRole;
import com.example.edam.model.SysRolePermission;
import com.example.edam.model.SysUserRole;
import com.example.edam.repository.SysPermissionRepository;
import com.example.edam.repository.SysRolePermissionRepository;
import com.example.edam.repository.SysRoleRepository;
import com.example.edam.repository.SysUserRoleRepository;
import com.example.edam.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RBAC 管理 Controller（v3.2 V-1）
 *
 * 全部写接口 @PreAuthorize 保护（role:manage / user:manage）；
 * 读接口要求 role:read 或 user:read；
 * admin 角色短路：hasAuthority('*:*') 永远通过（admin 用户的 roles 含 admin）。
 */
@Slf4j
@RestController
@RequestMapping("/rbac")
@RequiredArgsConstructor
@Tag(name = "rbac", description = "RBAC 权限管理")
public class RbacController {

    private final SysPermissionRepository permissionRepository;
    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final AuditService auditService;

    // ============== 权限目录（只读） ==============

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:read') or hasAuthority('user:read')")
    @Operation(summary = "权限目录列表")
    public List<PermissionDTO> listPermissions(
            @RequestParam(value = "resource_type", required = false) String resourceType) {
        List<SysPermission> list = (resourceType == null || resourceType.isBlank())
            ? permissionRepository.findAll()
            : permissionRepository.findByResourceType(resourceType);
        return list.stream().map(PermissionDTO::from).toList();
    }

    // ============== 角色 CRUD ==============

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    @Operation(summary = "角色列表（含权限）")
    public List<Map<String, Object>> listRoles() {
        List<SysRole> roles = roleRepository.selectList(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysRole r : roles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("code", r.getCode());
            item.put("name", r.getName());
            item.put("description", r.getDescription());
            item.put("is_system", r.getIsSystem());
            List<Long> permIds = rolePermRepository.findPermissionIdsByRoleId(r.getId());
            List<String> permCodes = permIds.isEmpty()
                ? List.of()
                : permissionRepository.selectBatchIds(permIds).stream()
                    .map(SysPermission::getCode).toList();
            item.put("permissions", permCodes);
            out.add(item);
        }
        return out;
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @Transactional
    @Operation(summary = "创建角色")
    public Map<String, Object> createRole(
            @RequestBody CreateRoleRequest req,
            @RequestHeader("X-User-Id") Long operatorId) {
        if (req.code == null || req.code.isBlank()) {
            throw new IllegalArgumentException("角色 code 必填");
        }
        if (roleRepository.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysRole>().eq("code", req.code)
        ).size() > 0) {
            throw new IllegalArgumentException("角色 code 已存在: " + req.code);
        }
        SysRole role = new SysRole();
        role.setCode(req.code);
        role.setName(req.name != null ? req.name : req.code);
        role.setDescription(req.description);
        role.setIsSystem(false);
        roleRepository.insert(role);

        if (req.permission_ids != null && !req.permission_ids.isEmpty()) {
            assignPermissionsInternal(role.getId(), req.permission_ids);
        }

        auditService.log(operatorId, "create_role", "role", role.getId(), "success",
            "code=" + req.code, null, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", role.getId());
        resp.put("code", role.getCode());
        resp.put("name", role.getName());
        return resp;
    }

    @PutMapping("/roles/{role_id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @Transactional
    @Operation(summary = "更新角色（权限全量替换）")
    public Map<String, Object> updateRole(
            @PathVariable("role_id") Long roleId,
            @RequestBody UpdateRoleRequest req,
            @RequestHeader("X-User-Id") Long operatorId) {
        SysRole role = roleRepository.selectById(roleId);
        if (role == null) throw new ResourceNotFoundException("角色不存在");
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new IllegalArgumentException("系统预置角色不可修改");
        }
        if (req.name != null) role.setName(req.name);
        if (req.description != null) role.setDescription(req.description);
        roleRepository.updateById(role);

        if (req.permission_ids != null) {
            rolePermRepository.deleteByRoleId(roleId);
            assignPermissionsInternal(roleId, req.permission_ids);
        }
        auditService.log(operatorId, "update_role", "role", roleId, "success", null, null, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", roleId);
        return resp;
    }

    @DeleteMapping("/roles/{role_id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @Transactional
    @Operation(summary = "删除角色（系统角色不可删）")
    public Map<String, Object> deleteRole(
            @PathVariable("role_id") Long roleId,
            @RequestHeader("X-User-Id") Long operatorId) {
        SysRole role = roleRepository.selectById(roleId);
        if (role == null) throw new ResourceNotFoundException("角色不存在");
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new IllegalArgumentException("系统预置角色不可删除");
        }
        // 软删除角色 + 清关联
        role.setDeletedAt(LocalDateTime.now());
        roleRepository.updateById(role);
        rolePermRepository.deleteByRoleId(roleId);
        userRoleRepository.delete(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserRole>()
                .eq("role_id", roleId));
        auditService.log(operatorId, "delete_role", "role", roleId, "success", null, null, null);
        return Map.of("id", roleId, "deleted", true);
    }

    @PostMapping("/roles/{role_id}/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    @Transactional
    @Operation(summary = "分配权限到角色（全量替换）")
    public Map<String, Object> assignPermissions(
            @PathVariable("role_id") Long roleId,
            @RequestBody AssignPermissionsRequest req,
            @RequestHeader("X-User-Id") Long operatorId) {
        SysRole role = roleRepository.selectById(roleId);
        if (role == null) throw new ResourceNotFoundException("角色不存在");

        rolePermRepository.deleteByRoleId(roleId);
        int n = assignPermissionsInternal(roleId, req.permission_ids);

        auditService.log(operatorId, "assign_permissions", "role", roleId, "success",
            "permission_count=" + n, null, null);
        return Map.of("role_id", roleId, "assigned_count", n);
    }

    private int assignPermissionsInternal(Long roleId, List<Long> permIds) {
        if (permIds == null) return 0;
        int n = 0;
        for (Long pid : permIds) {
            SysRolePermission srp = new SysRolePermission();
            srp.setRoleId(roleId);
            srp.setPermissionId(pid);
            rolePermRepository.insert(srp);
            n++;
        }
        return n;
    }

    // ============== 用户 ↔ 角色 ==============

    @GetMapping("/users/{user_id}/roles")
    @PreAuthorize("hasAuthority('user:read') or hasAuthority('role:read')")
    @Operation(summary = "查询用户的角色")
    public List<Map<String, Object>> getUserRoles(@PathVariable("user_id") Long userId) {
        List<SysRole> roles = userRoleRepository.findRolesByUserId(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysRole r : roles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("code", r.getCode());
            item.put("name", r.getName());
            out.add(item);
        }
        return out;
    }

    @PostMapping("/users/{user_id}/roles")
    @PreAuthorize("hasAuthority('user:manage')")
    @Transactional
    @Operation(summary = "分配角色给用户（全量替换）")
    public Map<String, Object> assignRolesToUser(
            @PathVariable("user_id") Long userId,
            @RequestBody AssignRolesRequest req,
            @RequestHeader("X-User-Id") Long operatorId) {
        int n = 0;
        if (req.role_ids != null && !req.role_ids.isEmpty()) {
            userRoleRepository.deleteByUserId(userId);
            for (Long rid : req.role_ids) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(rid);
                ur.setGrantedBy(operatorId);
                userRoleRepository.insert(ur);
                n++;
            }
        } else {
            // 空列表 = 清空
            userRoleRepository.deleteByUserId(userId);
        }
        auditService.log(operatorId, "assign_roles", "user", userId, "success",
            "role_count=" + n, null, null);
        return Map.of("user_id", userId, "assigned_count", n);
    }

    // ============== DTOs ==============

    @Data public static class CreateRoleRequest {
        public String code;
        public String name;
        public String description;
        public List<Long> permission_ids;
    }

    @Data public static class UpdateRoleRequest {
        public String name;
        public String description;
        public List<Long> permission_ids;
    }

    @Data public static class AssignPermissionsRequest {
        public List<Long> permission_ids;
    }

    @Data public static class AssignRolesRequest {
        public List<Long> role_ids;
    }
}
