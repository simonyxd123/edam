-- ============================================================================
-- U20260812_9000__rollback_init_schema.sql
-- 回滚 V20260812_1000__init_schema.sql + V20260812_2000__seed_baseline.sql
-- ============================================================================
-- 警告：
-- 1. 此脚本会删除所有表和数据，**不可逆**！
-- 2. 仅在初始化失败或测试环境使用
-- 3. 生产环境严禁执行
-- 4. 执行前必须确认已备份数据
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------------------------------------------------------
-- 1. 删除外键约束（避免依赖错误）
-- ----------------------------------------------------------------------------
-- MySQL 会自动级联删除，但显式删除更清晰

-- ----------------------------------------------------------------------------
-- 2. 删除 v3.1 新增表（按依赖逆序）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS backup;
DROP TABLE IF EXISTS webhook_delivery;
DROP TABLE IF EXISTS webhook;
DROP TABLE IF EXISTS doc_tag;
DROP TABLE IF EXISTS video_tag;
DROP TABLE IF EXISTS tag;
DROP TABLE IF EXISTS notification_preferences;
DROP TABLE IF EXISTS notification;

-- ----------------------------------------------------------------------------
-- 3. 删除审计溯源表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS external_doc_view_log;
DROP TABLE IF EXISTS driver_status;
DROP TABLE IF EXISTS key_rotation_log;
DROP TABLE IF EXISTS watermark_cache;
DROP TABLE IF EXISTS operation_log;
DROP TABLE IF EXISTS play_log;

-- ----------------------------------------------------------------------------
-- 4. 删除权限关联表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS distribution_approval_decision;
DROP TABLE IF EXISTS distribution_approval;
DROP TABLE IF EXISTS doc_permission;
DROP TABLE IF EXISTS video_permission;

-- ----------------------------------------------------------------------------
-- 5. 删除资源管理表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS file_metadata;
DROP TABLE IF EXISTS doc_resource;
DROP TABLE IF EXISTS video_resource;

-- ----------------------------------------------------------------------------
-- 6. 删除用户与权限表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS sys_session;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_dept;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 回滚完成
-- 验证：
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='edam';
-- 期望结果：0
-- ============================================================================