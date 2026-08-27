package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限目录（v3.2 V-1 RBAC 完整化）
 *
 * 对应表：sys_permission
 * 权限代码格式：<resource>:<action>，如 video:read / user:manage
 * 特殊值：*: *（admin 顶层权限，代码短路判断，不存 DB）
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    /** 资源域：video / document / user / role / system / ... */
    @TableField("resource_type")
    private String resourceType;

    /** 动作：read / upload / delete / edit / share / manage / ... */
    private String action;

    private String description;

    @TableField("is_system")
    private Integer isSystem;

    @TableField(fill = TableFieldFill.INSERT)
    private LocalDateTime createdAt;
}
