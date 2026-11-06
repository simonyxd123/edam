package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回调注册（webhook 表）
 *
 * <p>此前这张表在 Java 侧**没有任何 model / repository** ——
 * {@code POST /webhooks} 造一个假的 secret 和一个假的 ID 返回 201，
 * 但**什么都不落库**。用户以为注册成功了，之后却永远收不到任何回调。
 *
 * <p>{@code secret_hash} 存的是 secret 的 SHA-256（明文只在创建时返回一次，
 * 与常见 webhook 服务的做法一致）。
 *
 * <p><b>已知边界</b>：投递（delivery）链路尚未接线 ——
 * 签名需要 secret 明文，而表里只有哈希；首次投递也没有触发入口。
 * 详见 README 的「已知限制」。
 */
@Data
@TableName("webhook")
public class Webhook {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 注册人 */
    private Long ownerId;

    /** 回调地址 */
    private String url;

    /** 订阅的事件，逗号分隔 */
    private String events;

    /** secret 的 SHA-256 哈希（明文只在创建时返回一次） */
    private String secretHash;

    /** 1=active 2=paused 3=failed */
    private Integer status;

    private LocalDateTime lastDeliveredAt;

    private Integer failCount;

    private LocalDateTime createdAt;
}
