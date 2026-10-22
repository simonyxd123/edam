-- ===================================================================
-- V20260830_2000：对齐 Java model 的字段
-- ===================================================================
-- 背景：把 dev/db 的表结构合并进 backend 后，用脚本逐字段核对了
-- 全部 20 个 @TableName model（172 个字段），发现两处 schema 缺列：
--
--   1. video_resource.updated_at
--      model VideoResource.updatedAt (OffsetDateTime)
--      —— 原表只有 upload_time / deleted_at / version
--
--   2. webhook_delivery.created_at
--      model WebhookDelivery.createdAt (Instant)
--      —— 原表只有 delivered_at
--
-- MyBatis-Plus 写这两个字段时会抛
-- "Unknown column 'updated_at' in 'field list'"。
--
-- 说明：字段用 ADD COLUMN IF NOT EXISTS 语义处理（MySQL 8 不支持
-- IF NOT EXISTS，靠 Flyway 的版本号保证只跑一次即可）。
-- ===================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. video_resource.updated_at ==========================================
ALTER TABLE video_resource
    ADD COLUMN updated_at DATETIME(3) NULL COMMENT '最后更新时间' AFTER upload_time;

CREATE INDEX idx_video_updated_at ON video_resource (updated_at);

-- 2. webhook_delivery.created_at =======================================
ALTER TABLE webhook_delivery
    ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '记录创建时间' AFTER id;

CREATE INDEX idx_webhook_created_at ON webhook_delivery (created_at);
