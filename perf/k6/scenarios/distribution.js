// 外发审批场景：创建 / 审批 / 列表
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders, getRandomId } from '../lib/helpers.js';
import { apiTrend } from '../lib/metrics.js';

// 创建外发申请（POST /distribution）
export function createDistribution(token) {
  const docId = getRandomId(1000);
  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/distribution`,
    JSON.stringify({
      docId,
      recipients: ['user_a@example.com', 'user_b@example.com'],
      reason: 'k6 压测',
      expireHours: 24,
    }),
    authHeaders(token)
  );
  apiTrend.add(Date.now() - start);
  return check(res, {
    'distribution create 200/201': (r) => r.status === 200 || r.status === 201,
  });
}

// 审批外发申请（POST /distribution/{id}/approve）
export function approveDistribution(token) {
  const distId = getRandomId(1000);
  const res = http.post(
    `${BASE_URL}/distribution/${distId}/approve`,
    JSON.stringify({ decision: 'approved', comment: 'auto-approve by k6' }),
    authHeaders(token)
  );
  return check(res, {
    'distribution approve 200/404': (r) => r.status === 200 || r.status === 404,
  });
}

// 列出外发申请（GET /distribution）
export function listDistributions(token) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/distribution?page=1&page_size=20`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, { 'distribution list 200': (r) => r.status === 200 });
}