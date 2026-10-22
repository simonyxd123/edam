-- ============================================================================
-- V20260826_1000__add_webauthn.sql
-- v3.3 W-5：WebAuthn / FIDO2 无密码登录凭据表
-- =========================================================================：SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- webauthn_credential 表：FIDO2 凭据存储
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS webauthn_credential (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
    credential_id VARCHAR(512) NOT NULL COMMENT '凭据 ID（base64url）',
    public_key TEXT NOT NULL COMMENT '公钥（COSE 编码，base64url）',
    counter BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '签名计数器（防重放）',
    aaguid CHAR(36) NULL COMMENT '认证器 GUID',
    credential_type VARCHAR(32) NOT NULL DEFAULT 'platform' COMMENT 'platform / cross-platform',
    user_verification VARCHAR(32) NOT NULL DEFAULT 'preferred' COMMENT 'face / touch / pin / none',
    backup_eligible TINYINT(1) NOT NULL DEFAULT 0,
    backup_state TINYINT(1) NOT NULL DEFAULT 0,
    name VARCHAR(128) NULL COMMENT '凭据名称（用户自定义）',
    last_used_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    revoked_at DATETIME(3) NULL,
    revoked_reason VARCHAR(255) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential_id (credential_id(191)),
    KEY idx_user_id (user_id, revoked),
    KEY idx_last_used_at (last_used_at),
    CONSTRAINT fk_webauthn_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='WebAuthn / FIDO2 凭据表（v3.3 W-5）';

-- ----------------------------------------------------------------------------
-- webauthn_challenge 表：Challenge 备份（Redis 为主，DB 为备）
-- ----------------------------------------------------------------------------
-- 当前使用 Redis 存储（webauthn:challenge:{purpose}:{key}, TTL 300s）
-- Redis 故障时降级到 DB（不实现 v3.3）

-- 数据生命周期：
-- revoked = 1 的凭据保留 90 天（合规审计），过期硬删除
-- challenge 仅 Redis 存储，TTL 5 分钟自动过期