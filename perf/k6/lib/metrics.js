// k6 性能压测 - 自定义指标（v3.4 V4-03）
import { Rate, Trend, Counter } from 'k6/metrics';

// 错误率（业务级）
export const errorRate = new Rate('errors');

// 各场景延迟分布（毫秒）
export const loginTrend = new Trend('login_duration');
export const apiTrend = new Trend('api_duration');
export const watermarkTrend = new Trend('watermark_duration');
export const downloadTrend = new Trend('download_duration');
export const searchTrend = new Trend('search_duration');
export const auditTrend = new Trend('audit_duration');

// 计数类
export const successCounter = new Counter('success_total');
export const authSuccessCounter = new Counter('auth_success_total');
export const httpFailCounter = new Counter('http_fail_total');