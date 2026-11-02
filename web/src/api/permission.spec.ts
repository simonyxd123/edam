/**
 * 授权 API 的单测
 *
 * 重点验证两件事：
 * 1. **动作位掩码的取值**必须与后端 database_schema.md §3.1/§3.2 一致
 *    （位错了 = 授权给了不该给的能力）。
 * 2. **撤销用的是带 body 的 DELETE** —— user_ids 是数组，放不进 query string。
 *    如果哪天有人改回普通 delete，user_ids 会静默丢失。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ACTION, permissionApi } from './permission';

const mockPost = vi.fn();
const mockDeleteWithBody = vi.fn();
const mockGet = vi.fn();

vi.mock('./client', () => ({
  api: {
    post: (...args: unknown[]) => mockPost(...args),
    deleteWithBody: (...args: unknown[]) => mockDeleteWithBody(...args),
    get: (...args: unknown[]) => mockGet(...args),
  },
}));

beforeEach(() => {
  mockPost.mockReset().mockResolvedValue({ granted_count: 1 });
  mockDeleteWithBody.mockReset().mockResolvedValue({ revoked_count: 1 });
  mockGet.mockReset().mockResolvedValue({ items: [] });
});

describe('ACTION 位掩码', () => {
  it('与后端契约一致', () => {
    expect(ACTION.VIEW).toBe(1);
    expect(ACTION.DOWNLOAD).toBe(2);
    expect(ACTION.EDIT).toBe(4);
    expect(ACTION.DELETE).toBe(8);
    expect(ACTION.DISTRIBUTE).toBe(16);
    expect(ACTION.PRINT).toBe(32);
  });

  it('每一位都只占一个二进制位（不能重叠）', () => {
    const bits = [ACTION.VIEW, ACTION.DOWNLOAD, ACTION.EDIT, ACTION.DELETE, ACTION.DISTRIBUTE, ACTION.PRINT];
    for (const b of bits) {
      expect(b & (b - 1)).toBe(0);   // 2 的幂
      expect(b).toBeGreaterThan(0);
    }
  });
});

describe('视频授权', () => {
  it('授权走 POST + snake_case body', async () => {
    await permissionApi.grantVideo(7, [20, 30], ACTION.VIEW, '2026-12-01T00:00:00Z');

    const [url, body] = mockPost.mock.calls[0];
    expect(url).toBe('/videos/batch-permission');
    expect(body.video_id).toBe(7);
    expect(body.user_ids).toEqual([20, 30]);
    expect(body.actions).toBe(ACTION.VIEW);
    expect(body.expire_at).toBe('2026-12-01T00:00:00Z');
  });

  it('撤销走**带 body 的 DELETE**（user_ids 是数组，放不进 query string）', async () => {
    await permissionApi.revokeVideo(7, [20]);

    const [url, body] = mockDeleteWithBody.mock.calls[0];
    expect(url).toBe('/videos/batch-permission');
    expect(body.video_id).toBe(7);
    expect(body.user_ids).toEqual([20]);
  });

  it('查授权列表走 GET + query 参数', async () => {
    await permissionApi.listVideoPermissions(7);

    const [url, params] = mockGet.mock.calls[0];
    expect(url).toBe('/videos/batch-permission');
    expect(params).toEqual({ video_id: 7 });
  });
});

describe('文档授权', () => {
  it('授权走 POST + snake_case body', async () => {
    await permissionApi.grantDoc(3, [20], ACTION.VIEW | ACTION.PRINT);

    const [url, body] = mockPost.mock.calls[0];
    expect(url).toBe('/documents/batch-permission');
    expect(body.doc_id).toBe(3);
    expect(body.actions).toBe(ACTION.VIEW | ACTION.PRINT);
  });

  it('撤销走带 body 的 DELETE', async () => {
    await permissionApi.revokeDoc(3, [20]);

    const [url, body] = mockDeleteWithBody.mock.calls[0];
    expect(url).toBe('/documents/batch-permission');
    expect(body.doc_id).toBe(3);
  });
});
