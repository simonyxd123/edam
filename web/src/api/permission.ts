/**
 * 细粒度授权 API（视频 / 文档的 batch-permission）
 *
 * <p>后端模型见 doc/database_schema.md §3.1 / §3.2：
 * `actions` 是位掩码，同一 (资源, 用户) 唯一 ⇒ 再次授权是**覆盖**而不是叠加。
 */

import { api } from './client';

/** 动作位掩码（后端 database_schema.md §3.1 / §3.2） */
export const ACTION = {
  /** 查看（能播 / 能预览） */
  VIEW: 1,
  /** 下载原片 / 原文 */
  DOWNLOAD: 2,
  /** 编辑元数据 */
  EDIT: 4,
  /** 删除 */
  DELETE: 8,
  /** 再分享（把授权转授给别人） */
  DISTRIBUTE: 16,
  /** 打印（仅文档有这一位） */
  PRINT: 32,
} as const;

/** 授权结果 */
export interface PermissionGrant {
  user_id: number;
  actions: number;
  granted_by: number;
  granted_at: string;
  /** 为空 = 永不过期 */
  expire_at?: string | null;
}

/** 视频授权列表 */
export interface VideoPermissionList {
  video_id: number;
  items: PermissionGrant[];
}

/** 文档授权列表 */
export interface DocPermissionList {
  doc_id: number;
  items: PermissionGrant[];
}

export interface BatchPermissionResponse {
  granted_count: number;
  video_id?: number;
  doc_id?: number;
  actions: number;
  expire_at?: string | null;
}

export interface RevokePermissionResponse {
  revoked_count: number;
  video_id?: number;
  doc_id?: number;
}

export const permissionApi = {
  // ===== 视频 =====
  grantVideo: (videoId: number, userIds: number[], actions: number, expireAt?: string) =>
    api.post<BatchPermissionResponse>('/videos/batch-permission', {
      video_id: videoId,
      user_ids: userIds,
      actions,
      expire_at: expireAt,
    }),

  revokeVideo: (videoId: number, userIds: number[]) =>
    api.deleteWithBody<RevokePermissionResponse>('/videos/batch-permission', {
      video_id: videoId,
      user_ids: userIds,
    }),

  listVideoPermissions: (videoId: number) =>
    api.get<VideoPermissionList>('/videos/batch-permission', { video_id: videoId }),

  // ===== 文档 =====
  grantDoc: (docId: number, userIds: number[], actions: number, expireAt?: string) =>
    api.post<BatchPermissionResponse>('/documents/batch-permission', {
      doc_id: docId,
      user_ids: userIds,
      actions,
      expire_at: expireAt,
    }),

  revokeDoc: (docId: number, userIds: number[]) =>
    api.deleteWithBody<RevokePermissionResponse>('/documents/batch-permission', {
      doc_id: docId,
      user_ids: userIds,
    }),

  listDocPermissions: (docId: number) =>
    api.get<DocPermissionList>('/documents/batch-permission', { doc_id: docId }),
};
