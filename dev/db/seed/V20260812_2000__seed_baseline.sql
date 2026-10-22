-- ============================================================================
-- V20260812_2000__seed_baseline.sql
-- 基础种子数据：部门、预置角色、超级管理员
--
-- 【与迁移脚本的分工】
-- 权限目录（45 条）+ 角色权限分配 + 用户角色绑定 → 由
--   backend/src/main/resources/db/migration/V20260830_1000__rbac_seed.sql 负责
-- 本脚本只负责「部门」和「超级管理员账号」这类**数据**，不碰权限目录。
-- 原因：Flyway 的迁移是 schema + 目录的权威来源，seed 只是环境数据；
-- 两边都写权限目录会导致重复行与码值漂移。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. 部门
-- ----------------------------------------------------------------------------
INSERT INTO sys_dept (id, name, parent_id, path, level, sort_order) VALUES
    (1, '总公司', NULL, '/1/', 1, 0),
    (2, '技术部', 1, '/1/2/', 2, 1),
    (3, '产品部', 1, '/1/3/', 2, 2),
    (4, '安全部', 1, '/1/4/', 2, 3),
    (5, '后端组', 2, '/1/2/5/', 3, 1),
    (6, '前端组', 2, '/1/2/6/', 3, 2),
    (7, '基础架构组', 2, '/1/2/7/', 3, 3);

-- ----------------------------------------------------------------------------
-- 2. 角色（4 个预置角色）
-- ----------------------------------------------------------------------------
-- 角色码必须与代码一致：
--   PermissionService.isAdmin() / CurrentUser.isAdmin() 判 'admin'
--   @PreAuthorize 用的权限码也是 admin:backup / user:manage 这套
-- 原来的 super_admin / security_admin 与代码对不上 → 管理员判定永远不成立
INSERT INTO sys_role (code, name, description, is_system) VALUES
    ('admin',        '系统管理员', '系统全部权限，不可删除',     1),
    ('dept_manager', '部门经理',   '本部门资源管理与审批',     1),
    ('employee',     '普通员工',   '查看与使用本部门资源',     1),
    ('auditor',      '审计员',     '只读 + 审计导出',          1)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

-- 3. 超级管理员（密码：admin123，bcrypt cost=12 哈希示例）
-- 实际部署时必须修改默认密码
-- ----------------------------------------------------------------------------
INSERT INTO sys_user (
    username, password_hash, employee_no, real_name, email,
    dept_id, status, mfa_enabled
) VALUES (
    'admin',
    '$2a$12$LQv3c1yqBwEHxv6jO5b9WeFNa7qZf7xV8p9Q.5F5xQ5xQ5xQ5xQ5xQ',
    'SA0001',
    '系统管理员',
    'admin@example.com',
    4,  -- 安全部
    1,  -- active
    1   -- MFA 启用
);

-- 4. 给超级管理员分配 admin 角色
INSERT INTO sys_user_role (user_id, role_id, granted_at) VALUES (1, 1, NOW(3))
ON DUPLICATE KEY UPDATE granted_at = granted_at;

-- ============================================================================
-- 种子数据完成
-- 重要：默认密码 admin123 仅用于开发/测试环境，生产环境必须：
-- 1. 修改 admin 密码为强密码
-- 2. 启用 MFA
-- 3. 删除本初始化脚本中创建用户的默认密码
-- ============================================================================