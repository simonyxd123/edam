// 水印场景：提取 / 缓存
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders, getRandomId, jsonHeaders } from '../lib/helpers.js';
import { watermarkTrend } from '../lib/metrics.js';

// 水印提取（POST /watermarks/extract）
// 模拟上传一张图片并提取频域水印
export function extractWatermark(token) {
  const docId = getRandomId(1000);
  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/watermarks/extract`,
    JSON.stringify({ docId, algorithm: 'dct' }),
    authHeaders(token)
  );
  watermarkTrend.add(Date.now() - start);
  return check(res, {
    'watermark extract 200/404': (r) => r.status === 200 || r.status === 404,
  });
}

// 水印指纹缓存查询（GET /watermarks/cache/{hash}）
export function getWatermarkCache(token) {
  const hash = Math.random().toString(36).slice(2, 18);
  const start = Date.now();
  const res = http.get(`${BASE_URL}/watermarks/cache/${hash}`, authHeaders(token));
  watermarkTrend.add(Date.now() - start);
  return check(res, {
    'watermark cache 200/404': (r) => r.status === 200 || r.status === 404,
  });
}