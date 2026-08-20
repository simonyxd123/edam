// 文档场景：列表 / 详情 / 下载
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders, getRandomId } from '../lib/helpers.js';
import { apiTrend, downloadTrend } from '../lib/metrics.js';

// 列出文档（GET /documents）
export function listDocuments(token) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/documents?page=1&page_size=20`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, { 'list docs 200': (r) => r.status === 200 });
}

// 文档详情（GET /documents/{id}）
export function getDocument(token) {
  const docId = getRandomId(1000);
  const start = Date.now();
  const res = http.get(`${BASE_URL}/documents/${docId}`, authHeaders(token));
  apiTrend.add(Date.now() - start);
  return check(res, {
    'doc detail 200/404': (r) => r.status === 200 || r.status === 404,
  });
}

// 文档下载（GET /documents/{id}/download，触发水印嵌入）
export function downloadDocument(token) {
  const docId = getRandomId(1000);
  const start = Date.now();
  const res = http.get(`${BASE_URL}/documents/${docId}/download`, authHeaders(token));
  downloadTrend.add(Date.now() - start);
  return check(res, {
    'doc download 200/404': (r) => r.status === 200 || r.status === 404,
  });
}