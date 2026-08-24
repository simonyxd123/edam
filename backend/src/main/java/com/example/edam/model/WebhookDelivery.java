package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.Instant;

/**
 * Webhook 投递记录（v3.2 V-6）
 *
 * 每次 webhook 投递的结果：状态码、响应体、目标 URL、事件类型、payload 摘要
 */
@Data
@TableName("webhook_delivery")
public class WebhookDelivery {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Webhook 配置 ID（指向 webhook_config 表） */
    private Long webhookId;

    /** 事件类型，如 video.view, doc.download */
    private String event;

    /** 投递 payload（已序列化 JSON） */
    private String payload;

    /** HTTP 响应状态码（0 表示网络异常） */
    private Integer responseStatus;

    /** HTTP 响应体（截断到 1KB） */
    private String responseBody;

    /** 投递时间 */
    private Instant deliveredAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}