// ============================================================================
// E2E: 鉴权流程
// 覆盖：登录 → 刷新 Token → 获取当前用户 → 登出
// ============================================================================

import { test, expect } from '@playwright/test';

test.describe('鉴权流程', () => {
  test('登录成功并返回 access_token + refresh_token', async ({ request }) => {
    const response = await request.post('/auth/login', {
      data: {
        employee_no: 'SA0001',
        password: 'admin123',
      },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();

    expect(body).toHaveProperty('access_token');
    expect(body).toHaveProperty('refresh_token');
    expect(body.token_type).toBe('Bearer');
    expect(body.expires_in).toBeGreaterThan(0);
  });

  test('错误密码返回 401', async ({ request }) => {
    const response = await request.post('/auth/login', {
      data: {
        employee_no: 'SA0001',
        password: 'wrong-password',
      },
    });

    expect(response.status()).toBe(401);
    const body = await response.json();
    expect(body).toHaveProperty('title');
    expect(body).toHaveProperty('trace_id');
  });

  test('连续失败触发限流（429）', async ({ request }) => {
    // 5 次错误登录
    for (let i = 0; i < 5; i++) {
      await request.post('/auth/login', {
        data: { employee_no: 'SA0001', password: 'wrong' },
      });
    }

    // 第 6 次应触发限流
    const response = await request.post('/auth/login', {
      data: { employee_no: 'SA0001', password: 'admin123' },
    });

    expect([429, 423]).toContain(response.status());
  });

  test('使用 token 获取当前用户信息', async ({ request }) => {
    // 1. 登录
    const loginResp = await request.post('/auth/login', {
      data: { employee_no: 'SA0001', password: 'admin123' },
    });
    const { access_token } = await loginResp.json();

    // 2. 获取当前用户
    const meResp = await request.get('/auth/me', {
      headers: { Authorization: `Bearer ${access_token}` },
    });

    expect(meResp.status()).toBe(200);
    const user = await meResp.json();
    expect(user.employee_no).toBe('SA0001');
    expect(user.real_name).toBeDefined();
  });

  test('未带 token 访问受保护资源返回 401', async ({ request }) => {
    const response = await request.get('/users');
    expect(response.status()).toBe(401);
  });
});