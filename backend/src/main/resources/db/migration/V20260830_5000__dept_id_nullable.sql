-- ===================================================================
-- V20260830_5000：sys_user.dept_id 改为可空
-- ===================================================================
-- 背景：应用启动时 AdminBootstrap 要插入初始管理员，但那个账号
-- 并没有"部门归属"这回事 —— 结果：
--
--   Field 'dept_id' doesn't have a default value
--   INSERT INTO sys_user (username, password_hash, ..., updated_at) VALUES (...)
--
-- 即 sys_user.dept_id 是 NOT NULL 且无默认值，而插入时没给这个字段。
--
-- 为什么改成可空（而不是在代码里硬塞一个部门 id）：
--   1. Java model 的字段是 `private Long deptId;` —— **包装类型 = 可空**，
--      schema 的 NOT NULL 与 model 语义本来就不一致
--   2. "用户尚未分配部门"是合法业务状态（刚批量导入、待审批等）
--   3. 硬塞部门 id 会依赖 sys_dept 的种子数据顺序，且在没跑 seed 的
--      环境里会写成悬空引用
--
-- 外键约束 fk_user_dept 不受影响：MySQL 的外键允许被引用列为 NULL。
--
-- 注意：V20260812_1000（init）已经在多个环境成功应用，**不能改它**
-- （checksum 会对不上），所以用新版本号追加。
-- ===================================================================

SET NAMES utf8mb4;

ALTER TABLE sys_user
    MODIFY COLUMN dept_id BIGINT UNSIGNED NULL COMMENT '所属部门（未分配时为 NULL）';
