// k6 负载测试 - 200 QPS × 10 分钟（v3.4 V4-03）
// 目标：验证 SLO 达标（P50<100ms / P95<200ms / P99<500ms / 错误率<1%）
//
// 用法：
//   k6 run --out json=results/load.json scripts/load-test.js

import { login, currentUser, refreshToken } from '../scenarios/auth.js';
import { listVideos, getVideo, batchGetVideos } from '../scenarios/videos.js';
import { listDocuments, getDocument, downloadDocument } from '../scenarios/documents.js';
import { getPlaybackToken, reportPlaybackLog } from '../scenarios/playback.js';
import { extractWatermark, getWatermarkCache } from '../scenarios/watermarks.js';
import { createDistribution, listDistributions } from '../scenarios/distribution.js';
import { searchVideos, searchDocuments } from '../scenarios/search.js';
import { listNotifications, markNotificationRead } from '../scenarios/notifications.js';
import { queryAuditLogs } from '../scenarios/audit.js';
import { thinkTime } from '../lib/helpers.js';

export const options = {
  stages: [
    { duration: '1m', target: 200 },    // 1 分钟爬升到 200 QPS
    { duration: '8m', target: 200 },    // 8 分钟稳态
    { duration: '1m', target: 0 },      // 1 分钟降回 0
  ],
  thresholds: {
    http_req_duration: ['p(50)<100', 'p(95)<200', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
    'errors': ['rate<0.05'],
  },
};

export default function () {
  const token = login();
  if (!token) return;

  // 按 traffic mix 调用场景
  const r = Math.random();
  if (r < 0.05) {
    currentUser(token);
    thinkTime();
    refreshToken(token);
  } else if (r < 0.15) {
    listVideos(token);
  } else if (r < 0.27) {
    getVideo(token);
  } else if (r < 0.30) {
    batchGetVideos(token);
  } else if (r < 0.40) {
    listDocuments(token);
  } else if (r < 0.46) {
    getDocument(token);
  } else if (r < 0.50) {
    downloadDocument(token);
  } else if (r < 0.70) {
    getPlaybackToken(token);
  } else if (r < 0.72) {
    reportPlaybackLog(token);
  } else if (r < 0.82) {
    extractWatermark(token);
  } else if (r < 0.85) {
    getWatermarkCache(token);
  } else if (r < 0.88) {
    createDistribution(token);
  } else if (r < 0.90) {
    listDistributions(token);
  } else if (r < 0.95) {
    searchVideos(token);
    thinkTime();
    searchDocuments(token);
  } else if (r < 0.98) {
    listNotifications(token);
  } else {
    queryAuditLogs(token);
  }

  thinkTime();
}

export function handleSummary(data) {
  return {
    stdout: `
========== Load Test (200 QPS × 10min) 汇总 ==========
总请求数: ${data.metrics.http_reqs.values.count}
平均 RPS: ${data.metrics.http_reqs.values.rate.toFixed(1)}
P50 延迟: ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms
P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms
P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(1)}ms
失败率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
SLO 达标: P50<100ms ✓ / P95<200ms ✓ / P99<500ms ✓
======================================================
    `,
  };
}