// k6 极限测试 - 1000 QPS × 5 分钟（v3.4 V4-03）
// 目标：找瓶颈（不设阈值，看实际表现）
//
// 用法：
//   k6 run --out json=results/stress.json scripts/stress-test.js

import { login } from '../scenarios/auth.js';
import { listVideos } from '../scenarios/videos.js';
import { listDocuments } from '../scenarios/documents.js';
import { getPlaybackToken } from '../scenarios/playback.js';
import { extractWatermark } from '../scenarios/watermarks.js';
import { searchVideos } from '../scenarios/search.js';

export const options = {
  stages: [
    { duration: '30s', target: 500 },
    { duration: '1m', target: 1000 },
    { duration: '2m30s', target: 1000 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {},  // 压测不设阈值，看实际表现
};

export default function () {
  const token = login();
  if (!token) return;

  // 极限档只跑核心 5 类（找瓶颈）
  const r = Math.random();
  if (r < 0.30) {
    listVideos(token);
  } else if (r < 0.45) {
    listDocuments(token);
  } else if (r < 0.75) {
    getPlaybackToken(token);
  } else if (r < 0.90) {
    extractWatermark(token);
  } else {
    searchVideos(token);
  }
}

export function handleSummary(data) {
  return {
    stdout: `
========== Stress Test (1000 QPS × 5min) 汇总 ==========
总请求数: ${data.metrics.http_reqs.values.count}
平均 RPS: ${data.metrics.http_reqs.values.rate.toFixed(1)}
P50 延迟: ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms
P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms
P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(1)}ms
失败率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
最大并发 VU: ${data.metrics.vus ? data.metrics.vus.values.max : 'N/A'}
=========================================================
建议：对比 peak test 输出，找出首个出现错误率 > 5% 的 QPS 阈值。
    `,
  };
}