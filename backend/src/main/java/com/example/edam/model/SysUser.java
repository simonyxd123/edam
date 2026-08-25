package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户实体
 * 对应表：sys_user（参考 database_schema.md 1.2）
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String employeeNo;
    private String realName;
    private String email;
    private String phone;
    private Long deptId;

    /** 1=active 2=disabled 3=locked */
    private Integer status;

    private String mfaSecret;
    private Integer mfaEnabled;

    private OffsetDateTime lastLoginAt;
    private String lastLoginIp;
    private Integer failedLoginCount;

    /** 密码最近修改时间（v3.3 W-1 G-5 密码轮转策略） */
    private OffsetDateTime passwordChangedAt;

    /** 是否强制下次登录修改密码（管理员重置后置 true） */
    private Boolean mustChangePassword;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private OffsetDateTime deletedAt;

    /** 乐观锁（MP 3.5.7 在 updateById 上有 bug，临时注释；后续可升级 MP 或写自定义 update） */
    // @Version
    // private Integer version;
}