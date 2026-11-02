package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文档 ↔ 用户 授权关联（表 {@code doc_permission}）。
 *
 * <p>与 {@link VideoPermission} 同构，**多一个 {@code print} 位**
 * （依据 {@code doc/database_schema.md} §3.2）：
 *
 * <ul>
 *   <li>{@code 1=view 2=download 4=edit 8=delete 16=distribute 32=print}</li>
 *   <li>{@code expire_at} 为空 = 永不过期</li>
 *   <li>{@code granted_by} = 谁授的权</li>
 * </ul>
 *
 * <p>方案书 5.4 的"禁止打印 / 禁止复制下载"就落在这几个位上。
 *
 * @see com.example.edam.service.DocPermissionService
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TableName("doc_permission")
public class DocPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Long userId;

    /** 位掩码：1=view 2=download 4=edit 8=delete 16=distribute 32=print */
    private Integer actions;

    /** 授权人（FK→sys_user.id） */
    private Long grantedBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime grantedAt;

    /** 过期时间；为空 = 永不过期 */
    private OffsetDateTime expireAt;
}
