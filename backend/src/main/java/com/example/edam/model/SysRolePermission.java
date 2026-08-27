package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限 关联表（v3.2 V-1）
 *
 * 对应表：sys_role_permission
 * 一对多：每个角色可以拥有多个权限
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("role_id")
    private Long roleId;

    @TableField("permission_id")
    private Long permissionId;

    /** 预留约束表达式 JSON，如 {"max_count":100} */
    @TableField("constraint_def")
    private String constraintDef;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
