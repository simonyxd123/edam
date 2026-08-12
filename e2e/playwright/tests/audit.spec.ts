// ============================================================================
// E2E: 审计日志
// 覆盖：查询 → 详情 → 导出
// ============================================================================

import { test, expect } from '@playwright/test';

let accessToken: string;

test.beforeAll(async ({ request }) => {
  const resp = await request.post('/auth/login', {
    data: { employee_no: 'SA0001', password: 'admin123' },
  });
  accessToken = (await resp.json()).access_token;
});

test.describe('审计日志', () => {
  test('查询操作日志', async ({ request }) => {
    const resp = await request.get('/audit/logs?page=1&page_size=20', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('items');
    expect(body).toHaveProperty('pagination');
    expect(Array.isArray(body.items)).toBe(true);

    if (body.items.length > 0) {
      const log = body.items[0];
      expect(log).toHaveProperty('id');
      expect(log).toHaveProperty('user_id');
      expect(log).toHaveProperty('operation_type');
      expect(log).toHaveProperty('timestamp');
    }
  });

  test('按时间范围查询', async ({ request }) => {
    const start = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
    const end = new Date().toISOString();

    const resp = await request.get(
      `/audit/logs?start_time=${start}&end_time=${end}&page=1&page_size=10`,
      { headers: { Authorization: `Bearer ${accessToken}` } }
    );

    expect(resp.status()).toBe(200);
  });

  test('日志详情', async ({ request }) => {
    // 先获取一个日志
    const listResp = await request.get('/audit/logs?page_size=1', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const list = await listResp.json();

    if (list.items.length === 0) test.skip();

    const logId = list.items[0].id;
    const detailResp = await request.get(`/audit/logs/${logId}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    expect(detailResp.status()).toBe(200);
    const detail = await detailResp.json();
    expect(detail.id).toBe(logId);
  });

  test('异步导出日志', async ({ request }) => {
    const resp = await request.post('/audit/logs/export', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: {
        start_time: new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString(),
        end_time: new Date().toISOString(),
        format: 'csv',
      },
    });
    expect(resp.status()).toBe(202);
    const body = await resp.json();
    expect(body).toHaveProperty('task_id');
    expect(body).toHaveProperty('download_url');
  });
});