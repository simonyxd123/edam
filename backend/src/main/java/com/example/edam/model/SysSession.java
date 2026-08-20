package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统会话表（v3.2 V-8）
 *
 * 对应数据库表：sys_session
 * 关联用户的所有登录会话，支持：
 * - 多设备登录（每个 session_id 一台）
 * - 撤销（用户主动登出 / 管理员吊销）
 * - 自动过期（refresh_token TTL）
 */
@Data
@TableName("sys_session")
public class SysSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 会话 UUID（API 层用） */
    private String sessionId;

    /** refresh_token SHA-256 */
    private String refreshTokenHash;

    /** 当前 access_token JTI（用于早于过期前吊销） */
    private String accessTokenJti;

    /** 客户端 IP */
    private String ip;

    /** 浏览器 UA */
    private String userAgent;

    /** 设备指纹（可选） */
    private String deviceFingerprint;

    /** 登录方式：password / sso / mfa / webauthn */
    private String loginMethod;

    private LocalDateTime createdAt;

    private LocalDateTime lastActiveAt;

    /** refresh_token 过期时间 */
    private LocalDateTime expireAt;

    /** 当前 access_token 过期时间 */
    private LocalDateTime accessTokenExpireAt;

    /** 是否已撤销 */
    private Boolean revoked;

    private LocalDateTime revokedAt;

    /** 撤销原因 */
    private String revokedReason;

    @Version
    private Integer version;
}