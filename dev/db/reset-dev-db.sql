-- ===================================================================
-- EDAM 开发/测试库重置
-- ===================================================================
-- 什么时候需要跑这个：
--
--   1. Flyway 报 "Detected failed migration to version 20260812.1000"
--   2. Flyway 报 "Detected applied migration not resolved locally: 1"
--      （说明库里是旧版 backend V1/V2/V3 那套 schema）
--   3. Flyway 报外键 "are incompatible"（Error 3780）
--      （说明库里一半表是旧 schema 建的、另一半是新 init 建的）
--
-- 为什么必须重建而不是 repair：
--   旧 V1/V2/V3 与新 V2026* 两套 schema **结构不同**：
--     sys_user          旧版缺 must_change_password / password_changed_at
--     sys_role          旧版缺 deleted_at / version
--     sys_session       旧版缺 7 列
--     sys_role_permission / video_permission / doc_permission / play_log
--                       旧版是**完全不同的表结构**（permission/action/role_code
--                       vs permission_id/role_id/constraint_def）
--   而且外键两端的整数字符号性也不同（旧 signed BIGINT vs 新 UNSIGNED）。
--   这种情况下 repair + 手工补列的工作量与风险都远高于重建，
--   而且旧表里的数据本来也与当前 Java model 不兼容（读出来就是错的）。
--
-- ⚠️ 会清空该库全部数据。生产环境请勿执行。
-- ===================================================================

-- 1. 删库重建（推荐：最干净）
DROP DATABASE IF EXISTS edam;
CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 2. 验证
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'edam';

-- 3. 接下来：
--    重启后端 → Spring Flyway 会从零跑完整套迁移：
--      V20260812_1000__init_schema.sql
--      V20260820_1000__add_soft_del_and_session.sql
--      V20260825_1000__add_password_history.sql
--      V20260826_1000__add_webauthn.sql
--      V20260827_1000__add_fingerprint_library.sql
--      V20260828_1000__add_data_classification.sql
--      V20260830_1000__rbac_seed.sql
--      V20260830_2000__align_model_columns.sql
--
-- 4. 灌种子数据（可选，开发环境用）：
--    mysql -h<host> -u<user> -p edam < dev/db/seed/V20260812_2000__seed_baseline.sql
--
-- 5. 若 CI 的 db:migrate job 需要种子：
--    flyway ... -locations=filesystem:backend/src/main/resources/db/migration,filesystem:dev/db/seed migrate
