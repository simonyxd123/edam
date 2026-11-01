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
 * 视频 ↔ 用户 授权关联（表 {@code video_permission}）。
 *
 * <p><b>授权模型</b>（依据 {@code doc/database_schema.md} §3.1 与
 * {@code doc/openapi.yaml} 的 {@code /videos/batch-permission} 契约）：
 *
 * <ul>
 *   <li>{@code actions} 是**位掩码**，一位对应一种能力：
 *       {@code 1=view 2=download 4=edit 8=delete 16=distribute}（再分享）。
 *       一个用户对同一个视频只有一行记录（唯一键 {@code uk_video_user}），
 *       再次授权是**覆盖**而不是叠加。</li>
 *   <li>{@code expire_at} 为空表示**永不过期**；非空则过期后该行不再生效。</li>
 *   <li>{@code granted_by} 记录是谁授的权 —— 审计要用。</li>
 * </ul>
 *
 * <p><b>密级约束</b>：授权本身不能突破密级。
 * 上传者与管理员对 L3/L4 资源天然有权；普通员工即便被授权了 view，
 * 密级高于其可见范围时也不该放行（见
 * {@code com.example.edam.security.classification.ClassificationEnforcement}）。
 *
 * @see com.example.edam.service.VideoPermissionService
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TableName("video_permission")
public class VideoPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private Long userId;

    /** 位掩码：1=view 2=download 4=edit 8=delete 16=distribute */
    private Integer actions;

    /** 授权人（FK→sys_user.id） */
    private Long grantedBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime grantedAt;

    /** 过期时间；为空 = 永不过期 */
    private OffsetDateTime expireAt;
}
