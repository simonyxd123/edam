package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（notification 表）
 *
 * <p>此前这张表在 Java 侧**没有任何 model / repository** ——
 * {@code NotificationsController} 的所有端点都是写死的返回
 * （{@code items: []}、{@code unread_count: 0}、偏好设置原样回显请求体）。
 * 现在按真实表结构实现。
 *
 * <p><b>注意</b>：目前**没有任何生产者**往这张表写数据
 * （通知的触发时机是产品决策）。所以列表现在是空的 —— 那是**准确**的，
 * 不是 mock；将来接上生产者，不用改接口。
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    private Long userId;

    /** 通知类型：approval / key_alert / driver_alert / compliance / system */
    private String type;

    private String title;

    private String content;

    /** 关联资源类型（video / document / …） */
    private String relatedResourceType;

    private Long relatedResourceId;

    /** 0=未读 1=已读 */
    private Integer isRead;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;

    /** 过期时间；过期后不再展示 */
    private LocalDateTime expiresAt;
}
