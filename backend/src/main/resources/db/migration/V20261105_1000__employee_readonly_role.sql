-- V20261105_1000：普通员工角色收敛为「只读」
-- ===================================================================
-- 背景：employee（普通员工）角色原先带着 video:upload / document:upload，
--       也就是**任何一个普通员工都能往系统里塞文件**。在一个「防泄密」
--       系统里，这条授权方向是反的 —— 资产入库应当是受控动作（谁有权
--       往密级体系里写东西，比谁能读更要紧）。
--
-- 本次变更把 employee 收敛成**只读**：
--   能做：看仪表板 / 视频列表详情 / 文档列表详情 / 播放 / 预览 /
--         水印溯源 / 通知 / 标签 / 搜索 / 密级字典
--   不能：上传、编辑、删除、分享、下载原文、外发审批、管理类操作
--
-- 为什么用**白名单**而不是"删掉 upload 那两条"：
--   权限目录（sys_permission）会随功能增长。减法只能挡住已知的写权限，
--   以后新增一个 xxx:export 并在管理界面上勾给 employee，普通员工就会
--   默默地多出一项写权限。白名单的语义是「没显式列出来的，一律没有」——
--   新权限默认不给 employee。
--
-- 权威来源：backend/src/main/java/com/example/edam/security/EmployeePermissions.java
--   （那里的 READ_ONLY 白名单与本文件的白名单由 EmployeePermissionsTest
--     保证逐字一致，改一处必须改另一处）
--
-- 方案书对应章节：
--   §5.4 细粒度权限与外发管控 —— RBAC 角色权限划分，最小授权
--   §6.3 密级模型（L1-L4）—— 密级决定「谁能看」，角色决定「能做什么」
--   §14.3 STRIDE —— Elevation of Privilege（越权）：普通员工不得写入资产
--   §7.1 核心数据表 —— sys_role_permission 角色权限关联
--
-- 兼容性：纯授权数据变更，不动表结构、不动业务数据；
--         已绑定 employee 的用户权限会**立刻收窄**（JWT 里的权限码来自
--         登录时签发，用户需重新登录才拿到新权限集），
--         admin 不受影响（admin 走 PermissionService 短路，返回权限目录全集）。
-- ===================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. 权限目录补一条 =======================================================
-- WatermarkController 一直有 DELETE /watermarks/cache/{id}，但权限目录里
-- 只有 watermark:read / watermark:audit_export，没有任何 manage 类权限码 ——
-- 端点等于对所有登录用户敞开。补上这条，管理员才能显式授权。
INSERT INTO sys_permission (code, name, resource_type, action, description) VALUES
  ('watermark:manage', '水印管理', 'watermark', 'manage', '清除水印缓存等管理操作')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  resource_type = VALUES(resource_type),
  action = VALUES(action),
  description = VALUES(description);

-- 2. employee 收敛为只读 ==================================================
-- 2.1 先删：employee 身上所有不在白名单里的授权
DELETE rp
FROM sys_role_permission rp
JOIN sys_role r       ON r.id = rp.role_id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE r.code = 'employee'
  AND p.code NOT IN (
    'dashboard:read','video:read','document:read','watermark:read',
    'notification:read','tag:read','search:read','playback:read',
    'preview:read','classification:read'
  );

-- 2.2 再补：白名单里employee 还没拿到的授权
--     INSERT IGNORE：白名单里有一部分权限在 V20260830_1000 预置角色时
--     已经给过 employee 了，uk_role_permission(role_id, permission_id)
--     会让裸 INSERT 报重复键。
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
  'dashboard:read','video:read','document:read','watermark:read',
  'notification:read','tag:read','search:read','playback:read',
  'preview:read','classification:read'
)
WHERE r.code = 'employee';

-- 3. dept_manager 补齐「删除」=============================================
-- 原先只有 admin 能删（admin 走权限目录全集短路），dept_manager 明明有
-- video:upload / document:upload，上传完却删不掉自己传的东西 ——
-- 上传与删除的所有权不对称。
-- 补上 video:delete / document:delete 后，经理能删**自己上传**的资源
-- （删除的归属校验在 service 层：只有上传者本人或 admin 能删，见
--  VideoService.delete / DocumentService.delete），仍然删不了别人的。
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
  'video:delete','document:delete','watermark:manage'
)
WHERE r.code = 'dept_manager';

-- 4. auditor 不动 ========================================================
-- auditor 本来就是「只读 + 审计导出」，与新的 employee 基线不冲突。
-- 它保留 audit:read / audit:audit_export / leak:read / report:read
-- 这些**只读但敏感**的权限 —— 审计员就是干这个的。

-- 5. 事后自查（部署后可在生产库跑一遍确认）================================
-- SELECT r.code AS role_code, p.code AS perm_code, p.action
-- FROM sys_role_permission rp
-- JOIN sys_role r       ON r.id = rp.role_id
-- JOIN sys_permission p ON p.id = rp.permission_id
-- WHERE r.code = 'employee' ORDER BY p.code;
-- 期望：10 行，action 全是 'read'。
