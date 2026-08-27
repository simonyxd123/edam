package com.example.edam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.edam.model.SysPermission;
import lombok.Data;

/**
 * 权限 DTO（v3.2 V-1 RBAC 完整化）
 * 用 sys_permission 的 snake_case 视图
 */
@Data
public class PermissionDTO {

    private Long id;

    private String code;

    private String name;

    @JsonProperty("resource_type")
    private String resourceType;

    private String action;

    private String description;

    public static PermissionDTO from(SysPermission p) {
        PermissionDTO d = new PermissionDTO();
        d.id = p.getId();
        d.code = p.getCode();
        d.name = p.getName();
        d.resourceType = p.getResourceType();
        d.action = p.getAction();
        d.description = p.getDescription();
        return d;
    }
}
