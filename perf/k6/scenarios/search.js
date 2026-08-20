// 全文搜索场景：视频 / 文档
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { authHeaders } from '../lib/helpers.js';
import { searchTrend } from '../lib/metrics.js';

const KEYWORDS = ['会议', '合同', '财报', '制度', '培训', '产品', '客户', '项目', '安全', '合规'];

// 搜索视频（GET /search/videos?q=...）
export function searchVideos(token) {
  const q = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
  const start = Date.now();
  const res = http.get(
    `${BASE_URL}/search/videos?q=${encodeURIComponent(q)}&page=1&page_size=20`,
    authHeaders(token)
  );
  searchTrend.add(Date.now() - start);
  return check(res, { 'search videos 200': (r) => r.status === 200 });
}

// 搜索文档（GET /search/documents?q=...）
export function searchDocuments(token) {
  const q = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
  const start = Date.now();
  const res = http.get(
    `${BASE_URL}/search/documents?q=${encodeURIComponent(q)}&page=1&page_size=20`,
    authHeaders(token)
  );
  searchTrend.add(Date.now() - start);
  return check(res, { 'search documents 200': (r) => r.status === 200 });
}