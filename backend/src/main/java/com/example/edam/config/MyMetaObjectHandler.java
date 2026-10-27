package com.example.edam.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus 字段自动填充（v3.2 V-5）
 *
 * <p><b>填充规则</b>：实体字段上标了 {@code @TableField(fill = ...)} 就填，
 * 没标的一律不碰。
 *
 * <ul>
 *   <li>{@code INSERT} / {@code INSERT_UPDATE} → INSERT 时填</li>
 *   <li>{@code UPDATE} / {@code INSERT_UPDATE} → UPDATE 时填</li>
 * </ul>
 *
 * <p><b>为什么必须填</b>：MP 对标了 {@code fill} 的字段，生成 INSERT / UPDATE
 * 语句时是<b>无条件</b>带上该列的（见 {@code TableFieldInfo#getInsertSqlColumnMaybeIf}
 * 与 {@code #getSqlSet}：{@code withInsertFill} / {@code withUpdateFill} 为真时直接返回，
 * 不加 {@code <if>} 判空）。所以这些列的值<b>只能</b>由本类提供 ——
 * 填不上就是往 NOT NULL 列插 NULL，DB 侧那个
 * {@code DEFAULT CURRENT_TIMESTAMP(3)} 根本没机会生效。
 *
 * <p><b>为什么不用 MP 自带的 strictInsertFill</b>：
 * 它要求"属性名 + 属性类型<b>完全相等</b>"才填充
 * （{@code fieldType.equals(tableFieldInfo.getPropertyType())}），
 * 而项目里的时间字段有三种类型（OffsetDateTime / LocalDateTime / Instant），
 * 写死一种类型会让另外两种<b>静默不填</b>（不报错、不填值）。
 *
 * <p>曾经就因此炸过：全新库 + AdminBootstrap 自举时插入 sys_user_role，
 * {@code granted_at}（LocalDateTime）没被填上 ⇒
 * {@code Column 'granted_at' cannot be null} ⇒ dev profile 下 fail fast ⇒
 * 整个后端起不来。同样中招的还有 sys_role_permission.created_at、
 * sys_permission.created_at、webhook_delivery.created_at。
 *
 * <p>现在改成：按实体字段上的 {@code @TableField(fill=...)} 决定填哪些字段，
 * 再按字段的<b>声明类型</b>给出对应类型的时间值。
 *
 * <p><b>INSERT 与 UPDATE 的差别</b>：
 * <ul>
 *   <li>INSERT：只在字段为 null 时填（已显式赋值的不覆盖）</li>
 *   <li>UPDATE：每次都重填 —— {@code updated_at} 的语义就是"最后更新时间"。
 *       MP 的 SET 子句对 withUpdateFill 字段也是<b>无条件</b>带上该列的，
 *       这里不刷新，写回的就是旧值，时间戳会被冻结住</li>
 * </ul>
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(MyMetaObjectHandler.class);

    @Override
    public void insertFill(MetaObject metaObject) {
        fill(metaObject, FieldFill.INSERT);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        fill(metaObject, FieldFill.UPDATE);
    }

    /**
     * 按 {@code @TableField(fill=...)} 注解填充时间字段。
     *
     * @param phase {@link FieldFill#INSERT} 表示 INSERT 阶段，
     *               {@link FieldFill#UPDATE} 表示 UPDATE 阶段
     */
    private void fill(MetaObject metaObject, FieldFill phase) {
        Object entity = resolveEntity(metaObject);
        if (entity == null) {
            return;
        }
        // 一律对着实体本身操作：万一传进来的是包着 et 的 Map，
        // 对它 setValue 会写进 Map 而不是实体字段
        MetaObject target = (entity == metaObject.getOriginalObject())
                ? metaObject
                : SystemMetaObject.forObject(entity);

        for (Field field : allFields(entity.getClass())) {
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField == null || !tableField.exist()) {
                continue;
            }
            if (!applies(tableField.fill(), phase)) {
                continue;
            }
            String property = field.getName();
            if (!target.hasGetter(property) || !target.hasSetter(property)) {
                // 填不上 = 往 NOT NULL 列插 NULL，必须让日志吼出来，
                // 不能像以前那样静默跳过
                log.error("自动填充失败：{}.{} 找不到对应的 getter/setter，该列会被写成 NULL",
                        entity.getClass().getSimpleName(), property);
                continue;
            }
            // INSERT：已显式赋值的不覆盖
            // UPDATE：updated_at 必须每次刷新，否则时间戳被冻结
            if (phase == FieldFill.INSERT && target.getValue(property) != null) {
                continue;
            }
            Object value = nowValue(field.getType());
            if (value == null) {
                log.error("自动填充失败：{}.{} 的类型 {} 不是支持的时间类型，该列会被写成 NULL",
                        entity.getClass().getSimpleName(), property, field.getType().getSimpleName());
                continue;
            }
            target.setValue(property, value);
        }
    }

    /**
     * 取出实体对象。
     *
     * <p>MP 的 {@code @Param(Constants.ENTITY)}（键名 {@code et}）会把实体
     * 包在 Map 里传进来，这里做一次兜底解包。
     */
    private static Object resolveEntity(MetaObject metaObject) {
        Object original = metaObject.getOriginalObject();
        if (original instanceof Map) {
            return ((Map<?, ?>) original).get(Constants.ENTITY);
        }
        return original;
    }

    /** 该字段在当前阶段是否需要填充 */
    private static boolean applies(FieldFill declared, FieldFill phase) {
        if (declared == null || declared == FieldFill.DEFAULT) {
            return false;
        }
        if (phase == FieldFill.INSERT) {
            return declared == FieldFill.INSERT || declared == FieldFill.INSERT_UPDATE;
        }
        return declared == FieldFill.UPDATE || declared == FieldFill.INSERT_UPDATE;
    }

    /**
     * 按字段声明类型给出当前时间。
     *
     * <p><b>时间口径统一 UTC</b>：MySQL 侧 {@code --default-time-zone=+00:00}、
     * JDBC 侧 {@code serverTimezone=UTC}，DATETIME 列的默认值
     * {@code DEFAULT CURRENT_TIMESTAMP(3)} 也是 UTC 墙钟。
     * Connector/J 会把 OffsetDateTime / Instant 换算到 UTC 再落库，
     * 但 {@code LocalDateTime} <b>没有时区信息</b>，是<b>原样写入</b>的 ——
     * 所以这里必须直接给 UTC 墙钟，否则同一列里"DB 默认值"和"应用写入值"
     * 会差一个时区偏移（服务器 JVM 在 +08:00 时差 8 小时），跨列比较直接失真。
     */
    private static Object nowValue(Class<?> type) {
        Instant now = Instant.now();
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        }
        if (type == Instant.class) {
            return now;
        }
        if (type == Date.class) {
            return Date.from(now);
        }
        if (type == java.sql.Timestamp.class) {
            return java.sql.Timestamp.from(now);
        }
        return null;
    }

    /** 实体类及其父类的全部字段 */
    private static List<Field> allFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Collections.addAll(fields, c.getDeclaredFields());
        }
        return fields;
    }
}
