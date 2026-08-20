// k6 性能压测 - 全局配置（v3.4 V4-03）
//
// 通过环境变量覆盖：
//   BASE_URL           默认 http://localhost:8080/api/v1
//   HEALTH_URL         默认 http://localhost:8080
//   ENV_NAME           默认 dev
//   K6_USER_POOL_SIZE  默认 100

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
export const HEALTH_URL = __ENV.HEALTH_URL || 'http://localhost:8080';
export const ENV_NAME = __ENV.ENV_NAME || 'dev';

// 测试用户池（CI 环境需保证 TEST_001 ~ TEST_NNN 已 seed）
const POOL_SIZE = parseInt(__ENV.K6_USER_POOL_SIZE || '100', 10);
export const TEST_USERS = [];
for (let i = 1; i <= POOL_SIZE; i++) {
  TEST_USERS.push({
    employeeNo: `TEST_${String(i).padStart(3, '0')}`,
    password: 'TestP@ssw0rd!',
  });
}

// 流量配比（用于 mix 场景的总体分布）
export const TRAFFIC_MIX = {
  auth: 0.10,         // 登录/刷新/当前用户
  video_browse: 0.25, // 视频列表/详情
  video_play: 0.20,   // 播放 token/密钥
  document_browse: 0.10, // 文档列表
  document_download: 0.05, // 文档下载
  watermark: 0.10,    // 水印提取/缓存
  distribution: 0.05, // 外发审批
  search: 0.10,       // 全文搜索
  notifications: 0.03, // 通知
  audit: 0.02,        // 审计日志
};