-- ============================================================================
-- V20260812_2000__seed_baseline.sql
-- 基础种子数据：部门、权限、角色、超级管理员
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
-- 2. 权限定义（参考 openapi.yaml 中的权限代码）
-- ----------------------------------------------------------------------------
INSERT INTO sys_permission (code, name, resource_type) VALUES
    -- 视频权限
    ('video:read', '查看视频', 'video'),
    ('video:download', '下载视频', 'video'),
    ('video:upload', '上传视频', 'video'),
    ('video:delete', '删除视频', 'video'),
    ('video:distribute', '外发视频', 'video'),
    -- 文档权限
    ('document:read', '查看文档', 'document'),
    ('document:download', '下载文档', 'document'),
    ('document:upload', '上传文档', 'document'),
    ('document:delete', '删除文档', 'document'),
    ('document:distribute', '外发文档', 'document'),
    ('document:edit', '编辑文档', 'document'),
    ('document:print', '打印文档', 'document'),
    -- 系统权限
    ('system:user_manage', '用户管理', 'system'),
    ('system:role_manage', '角色管理', 'system'),
    ('system:audit_export', '审计导出', 'system'),
    ('system:backup', '备份管理', 'system'),
    ('system:settings', '系统设置', 'system');

-- ----------------------------------------------------------------------------
-- 3. 角色（4 个预置角色）
-- ----------------------------------------------------------------------------
INSERT INTO sys_role (code, name, description, is_system) VALUES
    ('super_admin', '超级管理员', '系统全部权限，不可删除', 1),
    ('security_admin', '安全管理员', '用户/角色/审计/备份管理', 1),
    ('dept_manager', '部门经理', '本部门资源管理与审批', 1),
    ('employee', '普通员工', '查看与使用本部门资源', 1);

-- 4. 角色权限关联
-- 4.1 super_admin → 全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission;

-- 4.2 security_admin → 系统管理 + 审计
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission WHERE code IN (
    'system:user_manage', 'system:role_manage', 'system:audit_export',
    'system:backup', 'system:settings'
);

-- 4.3 dept_manager → 部门级管理
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE code IN (
    'video:read', 'video:distribute', 'document:read', 'document:distribute',
    'document:print', 'document:upload'
);

-- 4.4 employee → 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE code IN (
    'video:read', 'document:read', 'document:download'
);

-- ----------------------------------------------------------------------------
-- 5. 超级管理员（密码：admin123，bcrypt cost=12 哈希示例）
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

-- 6. 给超级管理员分配 super_admin 角色
INSERT INTO sys_user_role (user_id, role_id, granted_at) VALUES (1, 1, NOW(3));

-- ============================================================================
-- 种子数据完成
-- 重要：默认密码 admin123 仅用于开发/测试环境，生产环境必须：
-- 1. 修改 admin 密码为强密码
-- 2. 启用 MFA
-- 3. 删除本初始化脚本中创建用户的默认密码
-- ============================================================================