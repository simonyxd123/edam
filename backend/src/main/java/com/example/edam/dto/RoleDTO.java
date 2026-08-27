package com.example.edam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.edam.model.SysRole;
import lombok.Data;

import java.util.List;

/**
 * 角色 DTO（v3.2 V-1 RBAC 完整化）
 * 含角色绑定的权限代码列表
 */
@Data
public class RoleDTO {

    private Long id;

    private String code;

    private String name;

    private String description;

    @JsonProperty("is_system")
    private Boolean isSystem;

    /** 该角色绑定的权限 code 列表（来自 sys_role_permission JOIN sys_permission） */
    private List<String> permissions;

    public static RoleDTO from(SysRole r) {
        RoleDTO d = new RoleDTO();
        d.id = r.getId();
        d.code = r.getCode();
        d.name = r.getName();
        d.description = r.getDescription();
        d.isSystem = r.getIsSystem();
        return d;
    }
}
