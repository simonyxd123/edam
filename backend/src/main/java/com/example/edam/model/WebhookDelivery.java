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

    /**
     * 这是第几次尝试（1 = 首次投递，2 = 第一次重试 …）。
     *
     * <p>表里本来就有这一列，但 model 一直没映射它（2026-11-08 模型对账发现），
     * 于是投递历史里**每一条看起来一模一样** ——
     * "失败了 3 次然后成功"和"一次就成功"在页面上分不出来。
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 下次重试的时间（null = 没有待重试的 / 已成功 / 已进死信）。
     *
     * <p>同样是表里早有这一列、model 缺失 ⇒ 运维在页面上**看不到
     * "这条还在等重试"**，只能靠猜。
     */
    @TableField("next_retry_at")
    private Instant nextRetryAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}