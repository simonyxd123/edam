package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知偏好（notification_preferences 表）
 *
 * <p>此前 {@code PUT /notifications/preferences} 的实现是
 * <b>原样回显请求体</b> —— 用户以为设置保存了，其实什么都没落库。
 * 现在按真实表结构实现。
 *
 * <p>{@code channels} / {@code types} 是 JSON 列，实体里按字符串存，
 * 由 Controller 负责序列化（避免为两个字段引入 JSON type handler）。
 */
@Data
@TableName("notification_preferences")
public class NotificationPreference {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一个用户只有一条偏好（表上有唯一键） */
    private Long userId;

    /** JSON：{"email":true,"sms":false,"im_webhook":true,"in_app":true} */
    private String channels;

    /** JSON：{"approval":true,"key_alert":true,...} */
    private String types;

    /** 0=不启用免打扰 1=启用 */
    private Integer quietHoursEnabled;

    /** 免打扰开始时间（本地时间 HH:mm:ss） */
    private String quietHoursStart;

    /** 免打扰结束时间 */
    private String quietHoursEnd;

    private LocalDateTime updatedAt;
}
