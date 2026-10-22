-- ============================================================================
-- V20260820_1000__add_soft_del_and_session.sql
-- v3.2 V-5 + V-8：sys_role 软删除 + sys_session 表
-- =========================================================================：SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. sys_role 表添加软删除字段（v3.2 V-5）
-- ----------------------------------------------------------------------------
ALTER TABLE sys_role
    ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '软删除时间' AFTER created_at,
    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁' AFTER deleted_at;

-- 创建索引支持软删除查询
CREATE INDEX idx_deleted_at ON sys_role (deleted_at);

-- ----------------------------------------------------------------------------
-- 2. sys_role_permission 表添加乐观锁字段
-- ----------------------------------------------------------------------------
ALTER TABLE sys_role_permission
    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 AFTER created_at;

-- ----------------------------------------------------------------------------
-- 3. sys_user_role 表添加乐观锁字段
-- ----------------------------------------------------------------------------
ALTER TABLE sys_user_role
    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 AFTER expire_at;

-- ----------------------------------------------------------------------------
-- 4. 创建 sys_session 表（v3.2 V-8，方案书 §13.1 引用）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL COMMENT '关联用户ID',
    session_id CHAR(64) NOT NULL COMMENT '会话 UUID',
    refresh_token_hash CHAR(64) NOT NULL COMMENT 'refresh_token SHA-256',
    access_token_jti VARCHAR(64) NULL COMMENT '当前 access_token JTI（用于早于过期前吊销）',
    ip VARCHAR(45) NULL COMMENT '客户端 IP（IPv6 兼容）',
    user_agent VARCHAR(512) NULL COMMENT '浏览器 UA',
    device_fingerprint VARCHAR(64) NULL COMMENT '设备指纹（可选）',
    login_method VARCHAR(32) NOT NULL DEFAULT 'password' COMMENT 'password / sso / mfa / webauthn',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_active_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    expire_at DATETIME(3) NOT NULL COMMENT 'refresh_token 过期时间',
    access_token_expire_at DATETIME(3) NULL COMMENT '当前 access_token 过期时间',
    revoked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已撤销',
    revoked_at DATETIME(3) NULL COMMENT '撤销时间',
    revoked_reason VARCHAR(255) NULL COMMENT '撤销原因（logout / revoke_keys / expire / security）',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user_id (user_id),
    KEY idx_expire_at (expire_at),
    KEY idx_refresh_token_hash (refresh_token_hash),
    KEY idx_access_token_jti (access_token_jti),
    KEY idx_revoked (revoked, last_active_at),
    CONSTRAINT fk_sys_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户会话表（v3.2 V-8）';

-- ----------------------------------------------------------------------------
-- 5. 初始化预置角色（v3.2 V-5 配套）
-- ----------------------------------------------------------------------------
-- 注：role 数据已通过 dev/db/seed 脚本初始化，本处不重复 INSERT

-- ----------------------------------------------------------------------------
-- 6. 数据生命周期
-- ----------------------------------------------------------------------------
-- 会话 7 天过期；凌晨清理任务删除 expire_at < NOW() AND revoked = 1 的记录
-- 建议 cron job: 每天 03:00 执行 DELETE FROM sys_session WHERE expire_at < DATE_SUB(NOW(), INTERVAL 30 DAY)