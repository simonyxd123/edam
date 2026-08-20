// k6 峰值测试 - 500 QPS × 10 分钟（v3.4 V4-03）
// 目标：验证弹性 + 告警
//
// 用法：
//   k6 run --out json=results/peak.json scripts/peak-test.js

import { login } from '../scenarios/auth.js';
import { listVideos, getVideo, batchGetVideos } from '../scenarios/videos.js';
import { listDocuments, getDocument, downloadDocument } from '../scenarios/documents.js';
import { getPlaybackToken, reportPlaybackLog } from '../scenarios/playback.js';
import { extractWatermark, getWatermarkCache } from '../scenarios/watermarks.js';
import { createDistribution, approveDistribution, listDistributions } from '../scenarios/distribution.js';
import { searchVideos, searchDocuments } from '../scenarios/search.js';
import { listNotifications, markNotificationRead } from '../scenarios/notifications.js';
import { queryAuditLogs, exportAuditLogs } from '../scenarios/audit.js';
import { thinkTime } from '../lib/helpers.js';

export const options = {
  stages: [
    { duration: '1m', target: 500 },    // 1 分钟爬升到 500 QPS
    { duration: '8m', target: 500 },    // 8 分钟稳态
    { duration: '1m', target: 0 },      // 1 分钟降回 0
  ],
  thresholds: {
    // 峰值档放宽阈值（业务可接受范围内）
    http_req_duration: ['p(50)<150', 'p(95)<300', 'p(99)<800'],
    http_req_failed: ['rate<0.02'],
    'errors': ['rate<0.05'],
  },
};

export default function () {
  const token = login();
  if (!token) return;

  const r = Math.random();
  if (r < 0.10) {
    listVideos(token);
  } else if (r < 0.20) {
    getVideo(token);
  } else if (r < 0.23) {
    batchGetVideos(token);
  } else if (r < 0.33) {
    listDocuments(token);
  } else if (r < 0.38) {
    getDocument(token);
  } else if (r < 0.43) {
    downloadDocument(token);
  } else if (r < 0.65) {
    getPlaybackToken(token);
  } else if (r < 0.67) {
    reportPlaybackLog(token);
  } else if (r < 0.78) {
    extractWatermark(token);
  } else if (r < 0.82) {
    getWatermarkCache(token);
  } else if (r < 0.85) {
    createDistribution(token);
  } else if (r < 0.87) {
    approveDistribution(token);
  } else if (r < 0.89) {
    listDistributions(token);
  } else if (r < 0.93) {
    searchVideos(token);
  } else if (r < 0.96) {
    searchDocuments(token);
  } else if (r < 0.98) {
    listNotifications(token);
  } else if (r < 0.99) {
    markNotificationRead(token);
  } else {
    queryAuditLogs(token);
  }

  // 峰值档不 sleep，模拟极端压力
}

export function handleSummary(data) {
  return {
    stdout: `
========== Peak Test (500 QPS × 10min) 汇总 ==========
总请求数: ${data.metrics.http_reqs.values.count}
平均 RPS: ${data.metrics.http_reqs.values.rate.toFixed(1)}
P50 延迟: ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms
P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms
P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(1)}ms
失败率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
======================================================
    `,
  };
}