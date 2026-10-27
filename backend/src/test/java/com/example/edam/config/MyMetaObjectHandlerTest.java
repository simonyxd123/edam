package com.example.edam.config;

import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.example.edam.model.SysUser;
import com.example.edam.model.SysUserRole;
import com.example.edam.model.WebhookDelivery;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MyMetaObjectHandler} 的自动填充单测。
 *
 * <p><b>为什么这个测试必须存在</b>：出过一次**启动失败**——
 * {@code AdminBootstrap} 绑定 admin 角色时插入 {@code sys_user_role}，
 * {@code granted_at} 却是 NULL，而该列是 {@code NOT NULL} ⇒
 * {@code Column 'granted_at' cannot be null} ⇒ dev profile 下 fail fast
 * ⇒ 整个后端起不来。
 *
 * <p>根因：旧的 handler 走 MP 的 {@code strictInsertFill}，
 * 而它要求"属性类型完全相等"才填充
 * （{@code fieldType.equals(tableFieldInfo.getPropertyType())}）。
 * handler 里写死的是 {@code OffsetDateTime.class}，
 * 而 {@code SysUserRole.grantedAt} 是 {@code LocalDateTime} ⇒
 * 填充被<b>静默跳过</b>（不报错、不填值）⇒ 插入 NULL。
 *
 * <p>受影响的还有 {@code sys_role_permission.created_at}、
 * {@code sys_permission.created_at}、{@code webhook_delivery.created_at}。
 */
class MyMetaObjectHandlerTest {

    private final MyMetaObjectHandler handler = new MyMetaObjectHandler();

    @Test
    @DisplayName("LocalDateTime 字段也能被填上（granted_at 就是这种）")
    void fillsLocalDateTimeFields() {
        SysUserRole entity = new SysUserRole();
        entity.setUserId(1L);
        entity.setRoleId(2L);
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertNotNull(entity.getGrantedAt(),
                "granted_at 必须被填上 —— 该列是 NOT NULL，填不上就是启动失败");
    }

    @Test
    @DisplayName("Instant 字段也能被填上（webhook_delivery.created_at 就是这种）")
    void fillsInstantFields() {
        WebhookDelivery entity = new WebhookDelivery();
        entity.setWebhookId(1L);
        entity.setEvent("video.view");
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertNotNull(entity.getCreatedAt(),
                "webhook_delivery.created_at 是 NOT NULL，填不上就插不进去");
    }

    @Test
    @DisplayName("OffsetDateTime 字段照常被填（不能被这次修复弄坏）")
    void stillFillsOffsetDateTimeFields() {
        SysUser entity = new SysUser();
        entity.setUsername("admin");
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
    }

    @Test
    @DisplayName("已显式赋值的字段不被覆盖")
    void doesNotOverwriteExplicitValue() {
        LocalDateTime explicit = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        SysUserRole entity = new SysUserRole();
        entity.setGrantedAt(explicit);
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertEquals(explicit, entity.getGrantedAt());
    }

    @Test
    @DisplayName("没有标注 fill 的字段不会被填")
    void leavesUnannotatedFieldsAlone() {
        SysUserRole entity = new SysUserRole();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        // expires_at 没有 fill 标注 ⇒ 必须保持 null
        assertNull(entity.getExpiresAt());
    }

    @Test
    @DisplayName("填进去的值是 UTC（与列的语义一致）")
    void fillsWithUtcTimestamp() {
        SysUserRole entity = new SysUserRole();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        OffsetDateTime nowUtc = OffsetDateTime.now(ZoneOffset.UTC);
        long delta = Math.abs(java.time.Duration.between(
                entity.getGrantedAt().atOffset(ZoneOffset.UTC).toInstant(),
                nowUtc.toInstant()).toSeconds());
        assertEquals(0, delta, "填充的时间应该是当前 UTC 时间");
    }

    @Test
    @DisplayName("UPDATE 阶段会刷新 updated_at（否则时间戳被冻结）")
    void updateRefreshesUpdatedAt() {
        SysUser entity = new SysUser();
        entity.setUsername("admin");
        entity.setUpdatedAt(OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.updateFill(metaObject);

        assertNotNull(entity.getUpdatedAt());
        assertTrue(entity.getUpdatedAt().isAfter(OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
                "updated_at 必须被刷新成当前时间，而不是沿用旧值");
    }

    @Test
    @DisplayName("UPDATE 阶段不碰 INSERT-only 的字段")
    void updateLeavesInsertOnlyFieldsAlone() {
        SysUser entity = new SysUser();
        entity.setUsername("admin");
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.updateFill(metaObject);

        // created_at 只在 INSERT 时填 ⇒ UPDATE 阶段必须保持 null
        assertNull(entity.getCreatedAt());
    }

    @Test
    @DisplayName("三种时间类型都映射到同一个 UTC 瞬时")
    void allTimeTypesShareSameInstant() {
        SysUserRole local = new SysUserRole();
        handler.insertFill(SystemMetaObject.forObject(local));
        WebhookDelivery instant = new WebhookDelivery();
        handler.insertFill(SystemMetaObject.forObject(instant));
        SysUser offset = new SysUser();
        handler.insertFill(SystemMetaObject.forObject(offset));

        Instant a = local.getGrantedAt().atOffset(ZoneOffset.UTC).toInstant();
        Instant b = instant.getCreatedAt();
        Instant c = offset.getCreatedAt().toInstant();
        assertTrue(Math.abs(a.getEpochSecond() - b.getEpochSecond()) <= 1,
                "LocalDateTime 与 Instant 必须表示同一时刻");
        assertTrue(Math.abs(a.getEpochSecond() - c.getEpochSecond()) <= 1,
                "LocalDateTime 与 OffsetDateTime 必须表示同一时刻");
    }

    @Test
    @DisplayName("java.util.Date 类型的字段也能被填上")
    void fillsDateFields() {
        DateHolder entity = new DateHolder();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertNotNull(entity.getCreatedAt());
    }

    @Test
    @DisplayName("实体被包在 et Map 里也能填上（MP 的 @Param(Constants.ENTITY) 约定）")
    void fillsEntityWrappedInMap() {
        SysUserRole entity = new SysUserRole();
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put(Constants.ENTITY, entity);
        MetaObject metaObject = SystemMetaObject.forObject(wrapper);

        handler.insertFill(metaObject);

        assertNotNull(entity.getGrantedAt(),
                "实体包在 et Map 里时也要能填上，否则 insert 一样会插入 NULL");
        assertNull(wrapper.get("grantedAt"),
                "不能把值写进 Map 的 key 上 —— 那样实体字段还是 null");
    }

    /** 只用来验证 Date 类型分支的最小实体 */
    static class DateHolder {
        @com.baomidou.mybatisplus.annotation.TableField(
                fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
        private Date createdAt;

        public Date getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Date createdAt) {
            this.createdAt = createdAt;
        }
    }
}
