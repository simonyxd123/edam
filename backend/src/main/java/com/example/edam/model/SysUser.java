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

    /**
     * 账号锁定到期时间（2026-11-14 代码审计第五轮）。
     *
     * <p>此前"锁定"只有 {@code status=3} 一个状态位，**没有到期时间**，
     * 于是"连续 5 次密码错误 → 锁定 30 分钟"实际上锁到管理员手工改库为止。
     *
     * <p>语义：
     * <ul>
     *   <li>非空 ⇒ 到期自动解锁（连续失败触发的自动锁定）</li>
     *   <li>为 null 且 status=3 ⇒ 管理员手动锁定，**不自动解锁**</li>
     * </ul>
     */
    private OffsetDateTime lockedUntil;

    /** 密码最近修改时间（v3.3 W-1 G-5 密码轮转策略） */
    private OffsetDateTime passwordChangedAt;

    /** 是否强制下次登录修改密码（管理员重置后置 true） */
    private Boolean mustChangePassword;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    // @TableLogic  // MP 3.5.7 在 OffsetDateTime deletedAt 上生成 WHERE deleted_at=0 永远查不到行；已废弃，全部改手写 AND deleted_at IS NULL
    private OffsetDateTime deletedAt;

    /** 乐观锁（MP 3.5.7 在 updateById 上有 bug，临时注释；后续可升级 MP 或写自定义 update） */
    // @Version
    // private Integer version;
}