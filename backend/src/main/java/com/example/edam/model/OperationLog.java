package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 操作日志实体
 * 对应表：operation_log（按月分表 operation_log_YYYYMM）
 *
 * <p>方案书 §7.1：审计与溯源 —— 播放日志表(play_log)、操作日志表(operation_log)。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 操作者。
     *
     * <p><b>可空</b>：登录失败时操作者身份尚未确认，写的是 NULL。
     * 这一列原来是 NOT NULL 且无默认值 ⇒ MySQL 直接拒绝这条 INSERT
     * ⇒ <b>失败登录的审计记录从来没写进过库</b>（V20261103_1000 修正）。
     */
    private Long userId;

    /** 操作类型，取值见 {@code AuditOpType}（login/list/detail/create/play/…） */
    private String operationType;

    /** 资源类型（video/document/user/role/audit_log/system…） */
    private String resourceType;

    /** 资源 ID */
    private Long resourceId;

    /** 真实客户端 IP（X-Forwarded-For → X-Real-IP → RemoteAddr） */
    private String ip;

    /** 浏览器 User-Agent（截断到 200 字符） */
    private String userAgent;

    /** HTTP 方法（GET/POST/PUT/PATCH/DELETE） */
    private String httpMethod;

    /** 命中的路由模板，如 {@code /videos/{video_id}} */
    private String endpoint;

    /** HTTP 响应状态码 */
    private Integer statusCode;

    /** 服务端处理耗时（毫秒） */
    private Integer durationMs;

    /** 会话 ID（JWT claims.session_id） */
    private String sessionId;

    /** 1=success 2=failure 3=denied */
    private Integer result;

    /** 操作详情（人读字符串，非 JSON） */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime timestamp;
}
