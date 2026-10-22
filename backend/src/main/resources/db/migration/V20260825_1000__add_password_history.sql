-- ============================================================================
-- V20260825_1000__add_password_history.sql
-- v3.3 W-1 G-5：密码历史记录 + 密码修改时间字段
-- =========================================================================：SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. sys_user 添加密码相关字段
-- ----------------------------------------------------------------------------
ALTER TABLE sys_user
    ADD COLUMN password_changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '密码最后修改时间（90 天轮转）' AFTER last_login_ip,
    ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否必须改密码' AFTER password_changed_at,
    ADD COLUMN pwd_history JSON NULL COMMENT '最近 5 次密码 hash（防重用）' AFTER must_change_password;

-- 索引
CREATE INDEX idx_pwd_changed_at ON sys_user (password_changed_at);

-- ----------------------------------------------------------------------------
-- 2. sys_password_history 表（更结构化的密码历史）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_password_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    password_hash VARCHAR(255) NOT NULL COMMENT 'bcrypt hash',
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    changed_by BIGINT UNSIGNED NULL COMMENT '修改人（用户自己/管理员）',
    change_reason VARCHAR(64) NOT NULL DEFAULT 'rotation' COMMENT 'rotation / reset / admin / security',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id, changed_at),
    CONSTRAINT fk_pwd_history_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='密码历史记录（保留最近 5 次）';

-- 数据生命周期：保留 5 次 × 90 天 = 450 天；过期清理（凌晨 cron）