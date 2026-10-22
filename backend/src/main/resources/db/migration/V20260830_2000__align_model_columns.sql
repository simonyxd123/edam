-- ===================================================================
-- V20260830_2000：对齐 Java model 的字段
-- ===================================================================
-- 背景：合并两套迁移脚本（backend 的 V1-V3 + dev/db 的 V2026*）时，
-- 发现 dev/db 的表结构与 Java model 有 5 处列不匹配：
--
--   1. sys_user_role 过期列叫 expire_at，model SysUserRole.expiresAt
--      映射到 expires_at → MyBatis 的角色过期过滤永远查不到
--   2. sys_permission 缺 action / description / is_system / created_at
--      （model SysPermission 有这 4 个字段）
--   3. sys_role_permission 缺 constraint_def / created_at
--      （model SysRolePermission 有这 2 个字段）
--   4. mime_type 是 VARCHAR(64) —— Excel 的 MIME
--      application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
--      有 66 字符 → Data truncation（这正是旧 V2__widen_mime_type 要修的问题）
--
-- MyBatis-Plus 写这些字段时会抛
-- "Unknown column 'xxx' in 'field list'"。
--
-- 【为什么放在新迁移里，而不是直接改 V20260812_1000】
-- Flyway 的铁律：**已应用过的迁移文件永不修改**（改了 checksum 就对不上，
-- 报 "Detected failed migration" / checksum mismatch）。
-- V20260812_1000 已经在某些环境里跑过，所以修正必须以新版本号追加。
-- ===================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. sys_user_role: expire_at → expires_at =============================
--    model 字段：SysUserRole.expiresAt
ALTER TABLE sys_user_role
    CHANGE COLUMN expire_at expires_at DATETIME(3) NULL COMMENT '角色过期时间';

-- 索引也跟着改名（原来叫 idx_expire_at）
ALTER TABLE sys_user_role
    DROP INDEX idx_expire_at,
    ADD INDEX idx_expires_at (expires_at);

-- 2. sys_permission: 补 model 需要的 4 列 ============================
--    model 字段：SysPermission.action / description / isSystem / createdAt
ALTER TABLE sys_permission
    ADD COLUMN action VARCHAR(32) NOT NULL DEFAULT '' COMMENT '动作：read/upload/edit/delete/...' AFTER resource_type,
    ADD COLUMN description VARCHAR(255) NULL COMMENT '权限说明' AFTER action,
    ADD COLUMN is_system TINYINT(1) NOT NULL DEFAULT 1 COMMENT '系统预置不可改' AFTER description,
    ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间' AFTER is_system;

-- 3. sys_role_permission: 补 model 需要的 2 列 =======================
--    model 字段：SysRolePermission.constraintDef / createdAt
ALTER TABLE sys_role_permission
    ADD COLUMN constraint_def VARCHAR(255) NULL COMMENT 'JSON 约束，如 {"max_count":100}' AFTER role_id,
    ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间' AFTER constraint_def;

-- 4. mime_type 拓宽到 255 =============================================
--    受影响：video_resource / doc_resource / file_metadata
ALTER TABLE video_resource
    MODIFY COLUMN mime_type VARCHAR(255) NULL;

ALTER TABLE doc_resource
    MODIFY COLUMN mime_type VARCHAR(255) NULL;

ALTER TABLE file_metadata
    MODIFY COLUMN mime_type VARCHAR(255) NULL;

-- 5. video_resource.updated_at / webhook_delivery.created_at ==========
--    model 字段：VideoResource.updatedAt / WebhookDelivery.createdAt
--    （这两张表原本就没有这两个列，写入时报 Unknown column）
ALTER TABLE video_resource
    ADD COLUMN updated_at DATETIME(3) NULL COMMENT '最后更新时间' AFTER upload_time;

CREATE INDEX idx_video_updated_at ON video_resource (updated_at);

ALTER TABLE webhook_delivery
    ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '记录创建时间' AFTER id;

CREATE INDEX idx_webhook_created_at ON webhook_delivery (created_at);

-- 6. sys_session: 补 init 建表时缺的 7 列 ============================
--
--    背景：sys_session 被**两个**迁移建过 ——
--      - V20260812_1000（init）里有 CREATE TABLE IF NOT EXISTS sys_session
--      - V20260820_1000 里又建了一次（列更全）
--    MySQL 的 CREATE TABLE IF NOT EXISTS 在表已存在时是**空操作**，
--    而 init 版本号更小、先跑 —— 所以最终生效的是 init 那份**缺 7 列**的
--    结构，V20260820 里那 7 列永远不会出现。
--
--    model SysSession 需要：accessTokenJti / deviceFingerprint /
--    loginMethod / accessTokenExpireAt / revokedAt / revokedReason / version
ALTER TABLE sys_session
    ADD COLUMN access_token_jti VARCHAR(64) NULL COMMENT '当前 access_token JTI' AFTER refresh_token_hash,
    ADD COLUMN device_fingerprint VARCHAR(64) NULL COMMENT '设备指纹（可选）' AFTER user_agent,
    ADD COLUMN login_method VARCHAR(32) NOT NULL DEFAULT 'password' COMMENT 'password/sso/mfa/webauthn' AFTER device_fingerprint,
    ADD COLUMN access_token_expire_at DATETIME(3) NULL COMMENT '当前 access_token 过期时间' AFTER expire_at,
    ADD COLUMN revoked_at DATETIME(3) NULL COMMENT '撤销时间' AFTER revoked,
    ADD COLUMN revoked_reason VARCHAR(255) NULL COMMENT '撤销原因' AFTER revoked_at,
    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁' AFTER revoked_reason;

CREATE INDEX idx_session_access_token_jti ON sys_session (access_token_jti);

