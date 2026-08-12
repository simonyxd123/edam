// ============================================================================
// E2E: 视频资源管理
// 覆盖：列表 → 上传 → 播放鉴权 → 详情
// ============================================================================

import { test, expect } from '@playwright/test';

let accessToken: string;

test.beforeAll(async ({ request }) => {
  const resp = await request.post('/auth/login', {
    data: { employee_no: 'SA0001', password: 'admin123' },
  });
  const body = await resp.json();
  accessToken = body.access_token;
});

test.describe('视频资源', () => {
  test('列表返回分页', async ({ request }) => {
    const resp = await request.get('/videos?page=1&page_size=10', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('items');
    expect(body).toHaveProperty('pagination');
    expect(Array.isArray(body.items)).toBe(true);
  });

  test('按密级过滤', async ({ request }) => {
    const resp = await request.get('/videos?classification_lv=L3', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    expect(resp.status()).toBe(200);
    const body = await resp.json();
    // 所有返回的视频都应是 L3
    body.items.forEach((video: any) => {
      expect(video.classification_lv).toBe('L3');
    });
  });

  test('上传视频（multipart/form-data）', async ({ request }) => {
    // 创建临时文件
    const buffer = Buffer.from('fake video content');

    const resp = await request.post('/videos', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: {
          name: 'test.mp4',
          mimeType: 'video/mp4',
          buffer,
        },
        classification_lv: 'L2',
        title: 'Test Video',
      },
    });

    expect([200, 202]).toContain(resp.status());
    const body = await resp.json();
    expect(body).toHaveProperty('video_id');
  });

  test('获取播放 token', async ({ request }) => {
    // 先找一个已有视频
    const listResp = await request.get('/videos?page_size=1', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const { items } = await listResp.json();

    if (items.length === 0) {
      test.skip(); // 没有视频时跳过
    }

    const videoId = items[0].id;
    const resp = await request.post(`/playback/${videoId}/token`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('m3u8_url');
    expect(body).toHaveProperty('key_url');
    expect(body).toHaveProperty('session_id');
  });
});