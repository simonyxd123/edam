// 通知场景：列表 / 标记已读
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders, getRandomId } from '../lib/helpers.js';
import { apiTrend } from '../lib/metrics.js';

// 通知列表（GET /notifications）
export function listNotifications(token) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/notifications?page=1&page_size=20`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, { 'notifications list 200': (r) => r.status === 200 });
}

// 标记已读（POST /notifications/{id}/read）
export function markNotificationRead(token) {
  const notifId = getRandomId(100);
  const res = http.post(`${BASE_URL}/notifications/${notifId}/read`, '{}', authHeaders(token));
  return check(res, {
    'mark read 200/204/404': (r) => r.status === 200 || r.status === 204 || r.status === 404,
  });
}