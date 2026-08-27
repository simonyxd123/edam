package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-角色 关联表（v3.2 V-1 RBAC 核心）
 *
 * 对应表：sys_user_role
 * 一对多：每个用户可以拥有多个角色
 * 特殊：expires_at 留空表示永久
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("role_id")
    private Long roleId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime grantedAt;

    /** 授权人（管理员 user_id） */
    @TableField("granted_by")
    private Long grantedBy;

    /** 过期时间（留空=永久） */
    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
