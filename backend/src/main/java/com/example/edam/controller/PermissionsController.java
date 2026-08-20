package com.example.edam.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限管理 Controller（v3.2 V-1 补全）
 * 对应 openapi.yaml tag: permissions
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "permissions", description = "权限管理")
public class PermissionsController {

    @GetMapping("/permissions")
    @Operation(summary = "权限列表")
    public List<Map<String, Object>> listPermissions() {
        // 预置权限代码（v3.1 P1-3 扩展）
        return List.of(
            Map.of("id", 1, "code", "video:read", "name", "查看视频", "resource_type", "video"),
            Map.of("id", 2, "code", "video:download", "name", "下载视频", "resource_type", "video"),
            Map.of("id", 3, "code", "video:upload", "name", "上传视频", "resource_type", "video"),
            Map.of("id", 4, "code", "video:delete", "name", "删除视频", "resource_type", "video"),
            Map.of("id", 5, "code", "document:read", "name", "查看文档", "resource_type", "document"),
            Map.of("id", 6, "code", "document:download", "name", "下载文档", "resource_type", "document"),
            Map.of("id", 7, "code", "document:upload", "name", "上传文档", "resource_type", "document"),
            Map.of("id", 8, "code", "document:delete", "name", "删除文档", "resource_type", "document"),
            Map.of("id", 9, "code", "document:edit", "name", "编辑文档", "resource_type", "document"),
            Map.of("id", 10, "code", "document:print", "name", "打印文档", "resource_type", "document"),
            Map.of("id", 11, "code", "system:audit_export", "name", "导出审计日志", "resource_type", "system"),
            Map.of("id", 12, "code", "system:user_manage", "name", "用户管理", "resource_type", "system"),
            Map.of("id", 13, "code", "system:role_manage", "name", "角色管理", "resource_type", "system")
        );
    }

    @GetMapping("/roles")
    @Operation(summary = "角色列表")
    public List<Map<String, Object>> listRoles() {
        return List.of(
            Map.of("id", 1, "code", "admin", "name", "系统管理员", "permissions", List.of("video:read", "video:download", "video:upload", "video:delete", "document:read", "document:upload", "document:delete", "system:audit_export", "system:user_manage", "system:role_manage")),
            Map.of("id", 2, "code", "dept_manager", "name", "部门经理", "permissions", List.of("video:read", "video:upload", "document:read", "document:upload", "document:edit")),
            Map.of("id", 3, "code", "auditor", "name", "审计员", "permissions", List.of("video:read", "document:read", "system:audit_export")),
            Map.of("id", 4, "code", "employee", "name", "普通员工", "permissions", List.of("video:read", "document:read"))
        );
    }

    @GetMapping("/users/{user_id}/permissions")
    @Operation(summary = "用户的有效权限（含 RBAC + 资源 ACL）")
    public List<Map<String, Object>> getUserPermissions(@PathVariable("user_id") Long userId) {
        // v3.2 占位实现，应调用 PermissionService 聚合 RBAC + 资源 ACL
        return List.of(
            Map.of("resource_type", "video", "resource_id", null, "actions", List.of("view", "download"), "source", "rbac", "expire_time", null),
            Map.of("resource_type", "document", "resource_id", null, "actions", List.of("view"), "source", "rbac", "expire_time", null)
        );
    }
}