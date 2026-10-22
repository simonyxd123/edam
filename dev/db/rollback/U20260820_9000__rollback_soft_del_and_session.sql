-- ============================================================================
-- U20260820_9000__rollback_soft_del_and_session.sql
-- 回滚 V20260820_1000
-- =========================================================================：SET NAMES utf8mb4;

DROP TABLE IF EXISTS sys_session;

ALTER TABLE sys_role DROP INDEX idx_deleted_at;
ALTER TABLE sys_role DROP COLUMN version;
ALTER TABLE sys_role DROP COLUMN deleted_at;

ALTER TABLE sys_role_permission DROP COLUMN version;
ALTER TABLE sys_user_role DROP COLUMN version;