-- ===================================================================
-- EDAM RBAC 完整化（V3）
-- 1. 备份旧 sys_role_permission（*/* 通配）→ 新建标准化权限目录
-- 2. 新增 sys_permission（权限目录）
-- 3. 新建 sys_role_permission（角色 → 权限 多对多）
-- 4. 新建 sys_user_role（用户 → 角色 多对多）
-- 5. 补齐 4 个预置角色 + 权限分配
-- 6. 给已有 admin 用户 + 默认 admin 账号分配角色
-- ===================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. 备份旧表（保留 1 个月可回滚） =====================================
RENAME TABLE sys_role_permission TO sys_role_permission_legacy;

-- 2. 权限目录 =================================================================
CREATE TABLE sys_permission (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  code          VARCHAR(64)  NOT NULL,        -- 如 video:read
  name          VARCHAR(128) NOT NULL,        -- 如 查看视频
  resource_type VARCHAR(32)  NOT NULL,        -- video / document / ...
  action        VARCHAR(32)  NOT NULL,        -- read / upload / ...
  description   VARCHAR(255),
  is_system     TINYINT(1)   NOT NULL DEFAULT 1,    -- 系统预置不可改
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_permission_code (code),
  KEY idx_sys_permission_resource (resource_type, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限目录';

-- 3. 角色 ↔ 权限 ========================================================
CREATE TABLE sys_role_permission (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  role_id       BIGINT       NOT NULL,
  permission_id BIGINT       NOT NULL,
  constraint_def VARCHAR(255),                -- JSON，如 {"max_count":100}（预留）
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_srp_role_perm (role_id, permission_id),
  KEY idx_srp_role (role_id),
  KEY idx_srp_perm (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限映射';

-- 4. 用户 ↔ 角色 ========================================================
CREATE TABLE sys_user_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  role_id     BIGINT       NOT NULL,
  granted_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  granted_by  BIGINT,
  expires_at  DATETIME(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sur_user_role (user_id, role_id),
  KEY idx_sur_user (user_id),
  KEY idx_sur_role (role_id),
  KEY idx_sur_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色映射';

-- 5. 种子数据：权限目录（45 条）==========================================
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
  ('mfa:manage',             'MFA 配置',           'mfa',          'manage',        NULL);

-- 6. 角色权限分配 ========================================================
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

-- 7. 已有用户迁移：默认 employee 角色 =======================
INSERT INTO sys_user_role (user_id, role_id, granted_by)
SELECT u.id, r.id, NULL
FROM sys_user u
CROSS JOIN sys_role r
WHERE r.code = 'employee'
  AND u.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role sur WHERE sur.user_id = u.id
  );

-- 8. 默认 admin 账号（工号 E000001）额外获得 admin 角色 ===================
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'admin'
WHERE u.employee_no = 'E000001'
ON DUPLICATE KEY UPDATE granted_at = granted_at;

-- 9. 强制所有旧会话 re-login（token 里的角色已过期）=====================
UPDATE sys_session
SET revoked = 1,
    revoked_at = CURRENT_TIMESTAMP(3),
    revoked_reason = 'rbac_v3_force_relogin'
WHERE revoked = 0;
