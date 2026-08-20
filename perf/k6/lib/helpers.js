// k6 性能压测 - 通用 helpers（v3.4 V4-03）

// 构造带 Bearer Token 的请求头
export function authHeaders(token) {
  return {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };
}

// 构造纯 JSON 请求头
export function jsonHeaders() {
  return { headers: { 'Content-Type': 'application/json' } };
}

// 随机取一个测试用户
export function getRandomUser(users) {
  return users[Math.floor(Math.random() * users.length)];
}

// 随机取 1..max 的 ID
export function getRandomId(max = 1000) {
  return Math.floor(Math.random() * max) + 1;

// 模拟用户思考时间（0.5 ~ 2.5 秒）
export function thinkTime() {
  return Math.random() * 2 + 0.5;
}

// 安全 JSON 解析（防止非 JSON 响应导致异常）
export function safeJson(res) {
  try {
    return res.json();
  } catch (e) {
    return {};
  }
}