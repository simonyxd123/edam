package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 标签实体（对应表 {@code tag}）。
 *
 * <p>此前标签功能是用 {@code static ConcurrentHashMap} 实现的占位 ——
 * 重启即丢、无法多实例共享、任何登录用户都能增删全局标签。
 * 表本来就在 schema 里，只是没有 model。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TableName("tag")
public class Tag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String code;

    /** video / document / both */
    private String type;

    /** 十六进制颜色，如 #FF0000 */
    private String color;

    private Integer useCount;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
