// ============================================================================
// E2E: 水印提取与溯源
// 覆盖：上传疑似泄露文件 → 提取水印 → 匹配用户
// ============================================================================

import { test, expect } from '@playwright/test';

let accessToken: string;

test.beforeAll(async ({ request }) => {
  const resp = await request.post('/auth/login', {
    data: { employee_no: 'SA0001', password: 'admin123' },
  });
  accessToken = (await resp.json()).access_token;
});

test.describe('水印溯源', () => {
  test('图片水印提取', async ({ request }) => {
    const buffer = Buffer.from('fake image content for testing');

    const resp = await request.post('/watermarks/extract', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: {
          name: 'leaked.png',
          mimeType: 'image/png',
          buffer,
        },
        type: 'image',
      },
    });

    expect(resp.status()).toBe(200);
    const result = await resp.json();
    expect(result.type).toBe('image');
    // extracted 字段可能为空（无水印文件）但结构必须存在
    expect(result).toHaveProperty('extracted');
    expect(result).toHaveProperty('matched_users');
    expect(Array.isArray(result.matched_users)).toBe(true);
  });

  test('PDF 水印提取', async ({ request }) => {
    const resp = await request.post('/watermarks/extract', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: {
          name: 'leaked.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('%PDF-1.4\nfake pdf'),
        },
        type: 'pdf',
      },
    });

    expect(resp.status()).toBe(200);
    const result = await resp.json();
    expect(result.type).toBe('pdf');
  });

  test('视频帧指纹比对', async ({ request }) => {
    const resp = await request.post('/watermarks/extract', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: {
          name: 'leaked.mp4',
          mimeType: 'video/mp4',
          buffer: Buffer.from('fake video content'),
        },
        type: 'video',
      },
    });

    expect(resp.status()).toBe(200);
    const result = await resp.json();
    expect(result.type).toBe('video');
    // 视频应返回 extracted.fingerprint（pHash 序列）
    expect(result.extracted).toHaveProperty('fingerprint');
  });

  test('水印缓存查询', async ({ request }) => {
    const resp = await request.get('/watermarks/cache?resource_id=1&resource_type=video', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    expect(resp.status()).toBe(200);
    const entries = await resp.json();
    expect(Array.isArray(entries)).toBe(true);
  });
});