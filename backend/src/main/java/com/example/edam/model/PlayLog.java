package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 播放日志实体
 * 对应表：play_log
 *
 * <p><b>为什么要有这张表</b>：{@code operation_log} 记的是"谁做了什么操作"，
 * 而播放日志记的是"谁把哪个视频看了多久、看到哪儿、期间有没有水印"——
 * 后者是**溯源**的证据链（方案书 §14.1 资产识别里把"审计日志"列为
 * 身份资产的一部分；§14.3 STRIDE 的 Repudiation 靠的就是这一条）。
 *
 * <p>方案书 §7.1：审计与溯源 —— 播放日志表(play_log)、操作日志表(operation_log)。
 * 方案书 §7.3：生命周期 —— <b>播放日志保留 365 天</b>（合规要求），
 * 比操作日志的 180 天更长。
 */
@Data
@TableName("play_log")
public class PlayLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 观看者 */
    private Long userId;

    /** 被观看的视频 */
    private Long videoId;

    /** 播放会话 ID（与播放 token 里的 session_id 一致） */
    private String sessionId;

    /** 访问时间（UTC） */
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime accessTime;

    private String ip;

    private String userAgent;

    /** 播放进度（秒） */
    private Integer progressSec;

    /** 事件类型：start / pause / seek / ended / error … */
    private String event;

    /** 是否已叠加动态水印 */
    private Integer watermarkApplied;

    /** 是否已提取指纹（服务端转码时算出来的） */
    private Integer fingerprintExtracted;
}
