// 视频场景：列表 / 详情 / 批量
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders, getRandomId } from '../lib/helpers.js';
import { apiTrend } from '../lib/metrics.js';

// 列出视频（GET /videos）
export function listVideos(token) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/videos?page=1&page_size=20`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, { 'list videos 200': (r) => r.status === 200 });
}

// 视频详情（GET /videos/{id}）
export function getVideo(token) {
  const videoId = getRandomId(1000);
  const start = Date.now();
  const res = http.get(`${BASE_URL}/videos/${videoId}`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, {
    'video detail 200/404': (r) => r.status === 200 || r.status === 404,
  });
}

// 批量查询（POST /videos/batch）
export function batchGetVideos(token) {
  const ids = Array.from({ length: 10 }, () => getRandomId(1000));
  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/videos/batch`,
    JSON.stringify({ ids }),
    authHeaders(token)
  );
  apiTrend.add(Date.now() - start);
  return check(res, { 'batch videos 200': (r) => r.status === 200 });
}