// k6 烟囱测试 - 10 VUs × 5 分钟（v3.4 V4-03）
// 目标：验证脚本链路 + 后端基本可用
//
// 用法：
//   k6 run --out json=results/smoke.json scripts/smoke.js
//   BASE_URL=http://staging.example.com/api/v1 k6 run scripts/smoke.js

import { login } from '../scenarios/auth.js';
import { listVideos, getVideo } from '../scenarios/videos.js';
import { listDocuments, getDocument } from '../scenarios/documents.js';
import { getPlaybackToken } from '../scenarios/playback.js';
import { extractWatermark } from '../scenarios/watermarks.js';
import { searchVideos } from '../scenarios/search.js';
import { thinkTime } from '../lib/helpers.js';

export const options = {
  vus: 10,
  duration: '5m',
  thresholds: {
    http_req_duration: ['p(99)<500', 'p(95)<200'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const token = login();
  if (!token) return;

  // 简化的链路验证（每次循环跑 6 个核心操作）
  listVideos(token);
  thinkTime();
  getVideo(token);
  thinkTime();
  listDocuments(token);
  thinkTime();
  getDocument(token);
  thinkTime();
  getPlaybackToken(token);
  thinkTime();
  extractWatermark(token);
  thinkTime();
  searchVideos(token);
}

export function handleSummary(data) {
  return {
    stdout: `
========== Smoke Test 汇总 ==========
总请求数: ${data.metrics.http_reqs.values.count}
平均 RPS: ${data.metrics.http_reqs.values.rate.toFixed(1)}
P50 延迟: ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms
P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms
P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(1)}ms
失败率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
========================================
    `,
  };
}