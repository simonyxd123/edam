-- ===================================================================
-- V20260830_1000：RBAC 目录与预置角色
-- ===================================================================
-- 背景：仓库里原本有**两套**互不兼容的迁移：
--   A) backend/src/main/resources/db/migration/  V1 / V2 / V3
--   B) dev/db/migration/                        V2026* 系列
-- 两套的表名、列名、版本号体系全都不同（A 的 sys_user_role 用 expires_at，
-- B 的用 expire_at；A 的 sys_permission 有 action/description/is_system，
-- B 的没有），任何一份先跑、另一份后跑都会因 validate-on-migrate 失败。
--
-- 现在统一为一套：B 的表结构（35 张表的超集）+ A 的 RBAC 数据。
-- 表结构在 V20260812_1000__init_schema.sql 里已经建好，
-- 本迁移只负责灌「权限目录 + 预置角色 + 角色权限分配 + 用户角色绑定」。
--
-- 权限码与角色码以**代码为准**：
--   - @PreAuthorize 用的是 admin:backup / distribution:approve / system:manage ...
--   - PermissionService.isAdmin() / CurrentUser.isAdmin() 判的是角色码 'admin'
--   所以这里用 admin / dept_manager / employee / auditor 这套码
-- ===================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. 预置角色 ===========================================================
INSERT INTO sys_role (code, name, description, is_system) VALUES
  ('admin',        '系统管理员',   '全部权限，不可删除',       1),
  ('dept_manager', '部门经理',     '本部门资源管理与审批',   1),
  ('employee',     '普通员工',     '查看与使用本部门资源',   1),
  ('auditor',      '审计员',       '只读 + 审计导出',        1)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

-- 2. 权限目录（45 条）===================================================
INSERT INTO sys_permission (code, name, resource_type, action, description) VALUES
  ('dashboard:read',         '查看仪表板',         'dashboard',    'read',          NULL),
  ('video:read',             '查看视频',           'video',        'read',          NULL),
  ('video:upload',           '上传视频',           'video',        'upload',        NULL),
  ('video:edit',             '编辑视频',           'video',        'edit',          NULL),
  ('video:delete',           '删除视频',           'video',        'delete',        NULL),
  ('video:download',         '下载视频',           'video',        'download',      NULL),
  ('video:share',            '分享视频',           'video',        'share',         NULL),
  ('document:read',          '查看文档',           'document',     'read',          NULL),
  ('document:upload',        '上传文档',           'document',     'upload',        NULL),
  ('document:edit',          '编辑文档',           'document',     'edit',          NULL),
  ('document:delete',        '删除文档',           'document',     'delete',        NULL),
  ('document:download',      '下载文档',           'document',     'download',      NULL),
  ('document:print',         '打印文档',           'document',     'print',         NULL),
  ('document:share',         '外发文档',           'document',     'share',         NULL),
  ('distribution:read',      '查看外发审批',       'distribution', 'read',          NULL),
  ('distribution:approve',   '审批外发',           'distribution', 'approve',       NULL),
  ('watermark:read',         '查看水印',           'watermark',    'read',          NULL),
  ('watermark:audit_export', '导出水印审计',       'watermark',    'audit_export',  NULL),
  ('audit:read',             '查看审计日志',       'audit',        'read',          NULL),
  ('audit:audit_export',     '导出审计日志',       'audit',        'audit_export',  NULL),
  ('leak:read',              '查看泄露检测',       'leak',         'read',          NULL),
  ('leak:manage',            '处理泄露事件',       'leak',         'manage',        NULL),
  ('user:read',              '查看用户',           'user',         'read',          NULL),
  ('user:manage',            '用户管理',           'user',         'manage',        NULL),
  ('role:read',              '查看角色',           'role',         'read',          NULL),
  ('role:manage',            '角色管理',           'role',         'manage',        NULL),
  ('system:read',            '查看系统配置',       'system',       'read',          NULL),
  ('system:manage',          '系统配置管理',       'system',       'manage',        NULL),
  ('admin:backup',           '触发备份',           'system',       'manage',        '高危：触发备份'),
  ('admin:restore',          '触发恢复',           'system',       'manage',        '高危：触发恢复'),
  ('webhook:read',           '查看 Webhook',       'webhook',      'read',          NULL),
  ('webhook:manage',         'Webhook 管理',       'webhook',      'manage',        NULL),
  ('notification:read',      '查看通知',           'notification', 'read',          NULL),
  ('notification:manage',    '通知管理',           'notification', 'manage',        NULL),
  ('tag:read',               '查看标签',           'tag',          'read',          NULL),
  ('tag:manage',             '标签管理',           'tag',          'manage',        NULL),
  ('report:read',            '查看报表',           'report',       'read',          NULL),
  ('report:audit_export',    '导出报表',           'report',       'audit_export',  NULL),
  ('playback:read',          '查看播放记录',       'playback',     'read',          NULL),
  ('search:read',            '全局搜索',           'search',       'read',          NULL),
  ('preview:read',           '预览资源',           'preview',      'read',          NULL),
  ('classification:read',    '查看密级',           'classification','read',         NULL),
  ('classification:manage',  '密级变更',           'classification','manage',       NULL),
  ('sso:manage',             'SSO 配置',           'sso',          'manage',        NULL),
  ('mfa:manage',             'MFA 配置',           'mfa',          'manage',        NULL)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  resource_type = VALUES(resource_type),
  action = VALUES(action),
  description = VALUES(description);

-- 3. 角色权限分配 ========================================================
-- admin：所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'admin';

-- dept_manager
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
  'dashboard:read','video:read','video:upload','video:edit','video:share',
  'document:read','document:upload','document:edit','document:share',
  'distribution:read','distribution:approve',
  'watermark:read','user:read','audit:read',
  'tag:read','tag:manage','classification:read','search:read'
)
WHERE r.code = 'dept_manager';

-- employee
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
  'dashboard:read','video:read','video:upload',
  'document:read','document:upload',
  'watermark:read','search:read'
)
WHERE r.code = 'employee';

-- auditor
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
  'dashboard:read','video:read','document:read',
  'audit:read','audit:audit_export',
  'leak:read','watermark:read','distribution:read',
  'playback:read','report:read'
)
WHERE r.code = 'auditor';

-- 4. 已有用户绑定默认 employee 角色 =======================================
INSERT INTO sys_user_role (user_id, role_id, granted_by)
SELECT u.id, r.id, NULL
FROM sys_user u
CROSS JOIN sys_role r
WHERE r.code = 'employee'
  AND u.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role sur WHERE sur.user_id = u.id
  );

-- 5. 默认 admin 账号（工号 E000001）额外获得 admin 角色 =================
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'admin'
WHERE u.employee_no = 'E000001'
ON DUPLICATE KEY UPDATE granted_at = granted_at;
