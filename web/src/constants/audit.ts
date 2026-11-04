/**
 * 审计操作类型清单（前端侧）
 *
 * <p><b>这份清单必须与后端 {@code com.example.edam.audit.AuditOpType} 一一对应。</b>
 * 2026-11-03 之前前端 Audit.vue 自己硬编码了
 * {@code ['login','logout','view','download','upload','delete','permission_change','config_change']}，
 * 而后端实际写进库里的是 {@code create / update / detail / preview / grant_permission …} ——
 * <b>两套名字几乎对不上</b>，于是审计页上的"操作类型"筛选框形同虚设：
 * 选了 {@code upload} 查不到任何东西，选了 {@code view} 也查不到。
 *
 * <p>现在后端把清单通过 {@code GET /audit/logs/operation-types} 暴露出来，
 * 前端下拉框<b>优先用后端返回的清单</b>，本地这份只作为：
 * <ul>
 *   <li>兜底（后端不可用时下拉框不至于空白）</li>
 *   <li>中文标签的来源（后端只给英文枚举，中文标签得前端有）</li>
 * </ul>
 *
 * <p>有单测 {@code auditConstants.spec.ts} 锁住两边的一致性。
 */

/** 规范操作类型（顺序即下拉框顺序） */
export const AUDIT_OP_TYPES = [
  'login',
  'logout',
  'token_refresh',
  'sso_login',
  'password_change',
  'profile_update',
  'list',
  'detail',
  'search',
  'play',
  'preview',
  'download',
  'create',
  'update',
  'delete',
  'batch_delete',
  'grant_permission',
  'revoke_permission',
  'config_change',
  'export',
  'access_denied',
] as const;

export type AuditOpType = (typeof AUDIT_OP_TYPES)[number];

/**
 * 操作类型 → 中文标签。
 *
 * <p>审计页是给审计员看的，全英文的 {@code grant_permission} 看不出是什么操作。
 */
export const AUDIT_OP_LABELS: Record<string, string> = {
  login: '登录',
  logout: '登出',
  token_refresh: '刷新令牌',
  sso_login: 'SSO 登录',
  password_change: '修改密码',
  profile_update: '修改资料',
  list: '查看列表',
  detail: '查看详情',
  search: '搜索',
  play: '播放',
  preview: '预览',
  download: '下载',
  create: '新增 / 上传',
  update: '修改',
  delete: '删除',
  batch_delete: '批量删除',
  grant_permission: '授予权限',
  revoke_permission: '撤销权限',
  config_change: '配置变更',
  export: '导出',
  access_denied: '访问被拒',
};

/**
 * 历史别名 → 规范名。
 *
 * <p>库里存的是老名字（{@code create_role}、{@code upload}、{@code view}…），
 * 后端查询时已经归并成规范名再返回；这份表用于**兜底** ——
 * 万一后端返回了没归并的老值，前端也能显示成人话。
 */
export const AUDIT_OP_LEGACY_ALIAS: Record<string, string> = {
  create_role: 'create',
  update_role: 'update',
  delete_role: 'delete',
  assign_permissions: 'grant_permission',
  assign_roles: 'grant_permission',
  reset_password: 'update',
  revoke_keys: 'update',
  permission_change: 'grant_permission',
  view: 'detail',
  upload: 'create',
};

/** 结果（success / failure / denied）的中文标签 */
export const AUDIT_RESULT_LABELS: Record<string, string> = {
  success: '成功',
  failure: '失败',
  denied: '拒绝',
};

/** 资源类型的中文标签（没列出的直接显示原值） */
export const AUDIT_RESOURCE_LABELS: Record<string, string> = {
  video: '视频',
  document: '文档',
  user: '用户',
  role: '角色',
  permission: '权限',
  auth: '鉴权',
  audit_log: '审计日志',
  tag: '标签',
  webhook: 'Webhook',
  webhook_delivery: '回调投递',
  notification: '通知',
  notification_preference: '通知偏好',
  watermark: '水印',
  watermark_cache: '水印缓存',
  distribution_approval: '外发审批',
  backup: '备份',
  dashboard: '仪表板',
  video_permission: '视频授权',
  document_permission: '文档授权',
  user_permission: '用户权限',
  user_role: '用户角色',
  webauthn_credential: 'WebAuthn 凭据',
  system: '系统',
};

/**
 * 取操作类型的中文标签（先归并历史别名，再查标签表）。
 */
export function auditOpLabel(op: string | undefined | null): string {
  if (!op) return '-';
  const canonical = AUDIT_OP_LEGACY_ALIAS[op] ?? op;
  return AUDIT_OP_LABELS[canonical] ?? canonical;
}

/**
 * 取资源类型的中文标签。
 */
export function auditResourceLabel(rt: string | undefined | null): string {
  if (!rt) return '-';
  return AUDIT_RESOURCE_LABELS[rt] ?? rt;
}

/**
 * 取结果的中文标签。
 */
export function auditResultLabel(result: string | undefined | null): string {
  if (!result) return '-';
  return AUDIT_RESULT_LABELS[result] ?? result;
}
