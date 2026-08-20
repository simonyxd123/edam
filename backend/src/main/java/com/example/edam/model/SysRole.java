package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统角色表（v3.2 V-5 含软删除 + 乐观锁）
 *
 * 对应数据库表：sys_role
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色代码（唯一，如 admin / dept_manager） */
    private String code;

    /** 角色名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 是否系统预置（不可删除） */
    private Boolean isSystem;

    private LocalDateTime createdAt;

    /** 软删除时间（v3.2 V-5 新增） */
    @TableLogic
    private LocalDateTime deletedAt;

    /** 乐观锁版本号（v3.2 V-5 新增） */
    @Version
    private Integer version;
}