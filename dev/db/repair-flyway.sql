-- ===================================================================
-- Flyway schema history 修复脚本
-- ===================================================================
-- 报错：
--   Validate failed: Migrations have failed validation
--   Detected failed migration to version 20260812.1000 (init schema).
--
-- 原因：V20260812_1000 在某个环境里**执行到一半失败**，
-- flyway_schema_history 里留下了一条 success = 0 的记录。
-- Flyway 看到失败记录就拒绝启动（这是保护机制 —— 防止在半成品
-- schema 上继续跑后续迁移）。
--
-- ⚠️ 执行前必须先做备份！
--
-- 【方案 A：开发/测试环境 —— 推荐】
-- 库本来就是给人折腾的，直接重建最干净：
--
--   DROP DATABASE edam;
--   CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--
-- 然后重启后端，Flyway 会从零跑完整套迁移
-- （V20260812_1000 → ... → V20260830_1000 → V20260830_2000）。
--
-- 【方案 B：生产环境 —— 不重建，走 repair】
-- 需要先清掉失败记录，再让 Flyway repair 修历史表。
-- 步骤见文件末尾的说明（repair 命令 + 半成品表清理）。
--
-- 本文件是**只读查询**，用于确认现状；真正的修复动作见注释里的说明。
-- ===================================================================

-- -------------------------------------------------------------------
-- 1. 查看 Flyway 历史里哪些迁移失败了
-- -------------------------------------------------------------------
SELECT installed_rank, version, description, type, script, checksum,
       installed_on, execution_time, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- 只看失败的
-- SELECT * FROM flyway_schema_history WHERE success = 0;

-- -------------------------------------------------------------------
-- 2. 看当前库里已有哪些表（判断"半成品"到什么程度）
-- -------------------------------------------------------------------
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY table_name;

-- -------------------------------------------------------------------
-- 3. 对照预期：35 张表
--    video_resource, doc_resource, sys_user, sys_role, sys_permission,
--    sys_role_permission, sys_user_role, sys_password_history, sys_session,
--    sys_dept, webauthn_credential, operation_log, play_log, leak_detection,
--    watermark_cache, webhook_delivery, webhook, notification,
--    notification_preferences, data_classification_audit,
--    data_classification_rule, distribution_approval,
--    distribution_approval_decision, doc_permission, video_permission,
--    doc_watermark, doc_tag, video_tag, tag, external_doc_view_log,
--    file_metadata, driver_status, key_rotation_log, video_fingerprint, backup
--
-- -------------------------------------------------------------------
-- 4. 修复动作（按你选定的方案执行，**不要两个都做**）
-- -------------------------------------------------------------------
--
-- 【方案 B 步骤 1】删掉失败记录
--   DELETE FROM flyway_schema_history WHERE success = 0;
--
-- 【方案 B 步骤 2】清掉半成品表（只删「已建但结构可能不完整」的表）
--   -- 危险操作，务必先备份。下面列出的是 init 脚本第一张表开始就会建的，
--   -- 如果失败发生在中途，后面的表根本不存在。
--   -- 先跑第 2 节的查询看清现状，再决定删哪些。
--
--   SET FOREIGN_KEY_CHECKS = 0;
--   DROP TABLE IF EXISTS sys_user_role;
--   DROP TABLE IF EXISTS sys_role_permission;
--   DROP TABLE IF EXISTS sys_permission;
--   -- ... 按实际存在情况逐个删
--   SET FOREIGN_KEY_CHECKS = 1;
--
-- 【方案 B 步骤 3】跑 repair 修历史表（Flyway CLI）
--   flyway -url=jdbc:mysql://<host>:3306/edam?allowPublicKeyRetrieval=true \
--          -user=<user> -password=<pass> \
--          -locations=filesystem:backend/src/main/resources/db/migration \
--          repair
--
-- 【方案 B 步骤 4】重启后端，Flyway 会重跑失败的迁移并继续后续版本。
