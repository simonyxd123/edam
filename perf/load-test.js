// k6 性能压测脚本（v3.3 W-15）
//
// 目标：
// - 模拟 1000 并发用户
// - 持续 10 分钟
// - 验证 P99 延迟 < 500ms

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const loginTrend = new Trend('login_duration');
const apiTrend = new Trend('api_duration');

export const options = {
  stages: [
    { duration: '1m', target: 100 },    // 1分钟爬升到100并发
    { duration: '3m', target: 500 },    // 3分钟爬升到500
    { duration: '5m', target: 1000 },   // 5分钟爬升到1000（峰值）
    { duration: '5m', target: 1000 },   // 5分钟持续1000并发
    { duration: '2m', target: 0 },      // 2分钟降回0
  ],
  thresholds: {
    http_req_duration: ['p(99)<500', 'p(95)<200'],   // P99 < 500ms
    http_req_failed: ['rate<0.01'],                    // 失败率 < 1%
    http_reqs: ['rate>100'],                           // 吞吐 > 100 RPS
    errors: ['rate<0.05'],                             // 错误率 < 5%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

// 测试用户凭证
const TEST_USERS = [];
for (let i = 1; i <= 100; i++) {
  TEST_USERS.push({
    employeeNo: `TEST_${String(i).padStart(3, '0')}`,
    password: 'TestP@ssw0rd!',
  });
}

export default function () {
  // 1. 登录（10% 用户）
  const shouldLogin = Math.random() < 0.1;
  if (shouldLogin) {
    const user = TEST_USERS[Math.floor(Math.random() * TEST_USERS.length)];
    const loginStart = Date.now();
    const loginRes = http.post(`${BASE_URL}/auth/login`, JSON.stringify({
      employeeNo: user.employeeNo,
      password: user.password,
    }), { headers: { 'Content-Type': 'application/json' } });
    loginTrend.add(Date.now() - loginStart);

    const loginOk = check(loginRes, {
      'login status is 200': (r) => r.status === 200,
      'login returns token': (r) => r.json('access_token') !== undefined,
    });
    if (!loginOk) {
      errorRate.add(1);
      return;
    }

    const token = loginRes.json('access_token');
    const authHeaders = {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    };

    // 2. 列出视频（30%）
    if (Math.random() < 0.3) {
      const listStart = Date.now();
      const listRes = http.get(`${BASE_URL}/videos?page=1&page_size=20`, authHeaders);
      apiTrend.add(Date.now() - listStart);
      check(listRes, { 'list videos 200': (r) => r.status === 200 });
    }

    // 3. 获取视频详情（30%）
    if (Math.random() < 0.3) {
      const videoId = Math.floor(Math.random() * 100) + 1;
      const detailStart = Date.now();
      const detailRes = http.get(`${BASE_URL}/videos/${videoId}`, authHeaders);
      apiTrend.add(Date.now() - detailStart);
      check(detailRes, { 'video detail 200': (r) => r.status === 200 || r.status === 404 });
    }

    // 4. 播放 token（20%）
    if (Math.random() < 0.2) {
      const videoId = Math.floor(Math.random() * 100) + 1;
      const tokenStart = Date.now();
      const tokenRes = http.post(`${BASE_URL}/playback/${videoId}/token`, '{}', authHeaders);
      apiTrend.add(Date.now() - tokenStart);
      check(tokenRes, { 'playback token 200': (r) => r.status === 200 });
    }

    // 5. 列出文档（10%）
    if (Math.random() < 0.1) {
      const docStart = Date.now();
      const docRes = http.get(`${BASE_URL}/documents?page=1&page_size=20`, authHeaders);
      apiTrend.add(Date.now() - docStart);
      check(docRes, { 'list docs 200': (r) => r.status === 200 });
    }

    sleep(Math.random() * 2 + 0.5); // 模拟用户思考时间
  } else {
    // 90% 健康检查（模拟监控）
    const healthRes = http.get(`${BASE_URL.replace('/api/v1', '')}/health/live`);
    check(healthRes, { 'health 200': (r) => r.status === 200 });
    sleep(1);
  }
}

export function handleSummary(data) {
  return {
    'perf/summary.json': JSON.stringify(data, null, 2),
    stdout: `
========== k6 压测结果汇总 ==========
总请求数: ${data.metrics.http_reqs.values.count}
平均 RPS: ${data.metrics.http_reqs.values.rate.toFixed(1)}
P50 延迟: ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms
P95 延迟: ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms
P99 延迟: ${data.metrics.http_req_duration.values['p(99)'].toFixed(1)}ms
失败率: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
错误率: ${(data.metrics.errors.values.rate * 100).toFixed(2)}%
========================================
    `,
  };
}