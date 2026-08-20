// 审计场景：日志查询 / 导出
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders } from '../lib/helpers.js';
import { auditTrend } from '../lib/metrics.js';

// 审计日志查询（GET /audit/logs）
export function queryAuditLogs(token) {
  const start = Date.now();
  const res = http.get(
    `${BASE_URL}/audit/logs?from=2026-08-01&to=2026-08-29&page=1&page_size=50`,
    authHeaders(token)
  );
  auditTrend.add(Date.now() - start);
  return check(res, { 'audit query 200': (r) => r.status === 200 });
}

// 审计日志导出（POST /audit/logs/export，异步任务触发）
export function exportAuditLogs(token) {
  const res = http.post(
    `${BASE_URL}/audit/logs/export`,
    JSON.stringify({ from: '2026-08-01', to: '2026-08-29', format: 'csv' }),
    authHeaders(token)
  );
  return check(res, {
    'audit export 202/200/404': (r) => r.status === 200 || r.status === 202 || r.status === 404,
  });
}