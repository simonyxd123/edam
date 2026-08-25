package com.example.edam.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * MyBatis-Plus 字段自动填充（v3.2 V-5）
 *
 * 触发条件：实体类字段标注 @TableField(fill = FieldFill.INSERT / INSERT_UPDATE)
 *
 * 已映射：
 *   - createdAt  ← INSERT 时填充
 *   - updatedAt  ← INSERT / UPDATE 时填充
 *
 * 若有其它字段也需要自动填充（如 deleted_at 软删除），按需扩展 fill 方法。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // 仅当实体类存在该字段时才填充，避免 strict=true 时 NPE
        TableInfo tableInfo = TableInfoHelper.getTableInfo(metaObject.getOriginalObject().getClass());
        if (tableInfo == null) {
            return;
        }

        // created_at / createdAt
        strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "created_at", OffsetDateTime.class, now);

        // updated_at / updatedAt
        strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updated_at", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        strictUpdateFill(metaObject, "updated_at", OffsetDateTime.class, now);
    }
}