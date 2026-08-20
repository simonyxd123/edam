// 播放鉴权场景：播放 Token / 密钥 / 日志上报
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders, getRandomId, jsonHeaders } from '../lib/helpers.js';
import { apiTrend } from '../lib/metrics.js';

// 获取播放 Token（POST /playback/{id}/token）
export function getPlaybackToken(token) {
  const videoId = getRandomId(1000);
  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/playback/${videoId}/token`,
    JSON.stringify({ expireSeconds: 3600 }),
    authHeaders(token)
  );
  apiTrend.add(Date.now() - start);
  return check(res, {
    'playback token 200/404': (r) => r.status === 200 || r.status === 404,
  });
}

// 获取解密密钥（GET /playback/{id}/key）
// 注：实际返回经 secure_link 校验后的 AES key，此处仅校验接口可用
export function getPlaybackKey(token, videoId) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/playback/${videoId}/key`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, {
    'playback key 200/404': (r) => r.status === 200 || r.status === 404,
  });
}

// 上报播放日志（POST /playback/log）
export function reportPlaybackLog(token) {
  const videoId = getRandomId(1000);
  const res = http.post(
    `${BASE_URL}/playback/log`,
    JSON.stringify({
      videoId,
      duration: Math.floor(Math.random() * 600),
      timestamp: Date.now(),
    }),
    authHeaders(token)
  );
  return check(res, { 'playback log 200/204': (r) => r.status === 200 || r.status === 204 });
}