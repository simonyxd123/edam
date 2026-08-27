package com.example.edam.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * MyBatis-Plus 字段自动填充（v3.2 V-5）
 *
 * 触发条件：实体类字段标注 @TableField(fill = FieldFill.INSERT / INSERT_UPDATE)
 *
 * 已映射（INSERT 时填）：
 *   createdAt / created_at           — 通用创建时间
 *   updatedAt / updated_at           — 更新时间（INSERT 也填）
 *   uploadTime / upload_time         — 上传时间（视频 / 文档）
 *   lastLoginAt / last_login_at      — 最后登录时间
 *   passwordChangedAt / password_changed_at — 密码修改时间
 *   timestamp                        — 审计日志时间
 *   detectedAt / detected_at         — 泄露检测时间
 *   deliveredAt / delivered_at       — webhook 投递时间
 *
 * 已映射（UPDATE 时填）：
 *   updatedAt / updated_at
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** INSERT 时填的所有时间字段（驼峰 + 下划线各一份） */
    private static final String[] INSERT_FIELDS = {
        "createdAt", "created_at",
        "updatedAt", "updated_at",
        "uploadTime", "upload_time",
        "lastLoginAt", "last_login_at",
        "passwordChangedAt", "password_changed_at",
        "timestamp",
        "detectedAt", "detected_at",
        "deliveredAt", "delivered_at",
        "expireAt", "expire_at",
        "accessTokenExpireAt", "access_token_expire_at",
        "lastActiveAt", "last_active_at",
        "grantedAt", "granted_at",   // v3.2 V-1 RBAC: sys_user_role.granted_at
        "created", "updated",
    };

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String field : INSERT_FIELDS) {
            strictInsertFill(metaObject, field, OffsetDateTime.class, now);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        strictUpdateFill(metaObject, "updated_at", OffsetDateTime.class, now);
    }
}