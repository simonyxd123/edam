package com.example.edam.controller;

import com.example.edam.dto.PermissionDTO;
import com.example.edam.repository.SysPermissionRepository;
import com.example.edam.repository.SysRoleRepository;
import com.example.edam.repository.SysUserRoleRepository;
import com.example.edam.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限管理 Controller（兼容旧接口 + 委托到 RBAC 真实数据）
 *
 * v3.2 V-1 RBAC 完整化后，旧 /permissions /roles /users/{id}/permissions 接口
 * 委托给 sys_permission / sys_role 表（新 RbacController 才是首选入口）。
 *
 * 标注 @Deprecated 保留向后兼容（前端老调用），新代码用 /rbac/*。
 */
@Deprecated
@RestController
@RequestMapping
@RequiredArgsConstructor
@Tag(name = "permissions", description = "权限管理（已废弃，请用 /rbac/*）")
public class PermissionsController {

    private final SysPermissionRepository permissionRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PermissionService permissionService;

    @GetMapping("/permissions")
    @Operation(summary = "权限列表（已废弃，用 /rbac/permissions）")
    public List<PermissionDTO> listPermissions() {
        return permissionRepository.findAll().stream()
            .map(PermissionDTO::from)
            .toList();
    }

    @GetMapping("/roles")
    @Operation(summary = "角色列表（已废弃，用 /rbac/roles）")
    public List<Map<String, Object>> listRoles() {
        // 委托给 RbacController 的逻辑：列角色 + 关联权限 code
        return roleRepository.selectList(null).stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("code", r.getCode());
            item.put("name", r.getName());
            item.put("permissions", List.of());  // 简化：旧接口不返回权限
            return item;
        }).toList();
    }

    @GetMapping("/users/{user_id}/permissions")
    @Operation(summary = "用户有效权限（已废弃，用 /auth/me 里的 permissions 字段）")
    public List<Map<String, Object>> getUserPermissions(@PathVariable("user_id") Long userId) {
        // 委托给 PermissionService 真实数据
        var codes = permissionService.getUserPermissionCodes(userId);
        return codes.stream().map(code -> {
            Map<String, Object> item = new LinkedHashMap<>();
            String[] parts = code.split(":", 2);
            item.put("resource_type", parts[0]);
            item.put("action", parts.length > 1 ? parts[1] : "");
            item.put("code", code);
            item.put("source", "rbac");
            return item;
        }).toList();
    }
}
