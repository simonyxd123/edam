// 鉴权场景：登录 / 刷新 / 当前用户 / 登出
// v3.4 V4-03
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, TEST_USERS } from '../lib/config.js';
import { authHeaders, jsonHeaders, getRandomUser, safeJson } from '../lib/helpers.js';
import { loginTrend, errorRate } from '../lib/metrics.js';

// 登录（POST /auth/login）
// 返回 access_token 字符串，失败返回 null
export function login() {
  const user = getRandomUser(TEST_USERS);
  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ employeeNo: user.employeeNo, password: user.password }),
    jsonHeaders()
  );
  loginTrend.add(Date.now() - start);

  const ok = check(res, {
    'login status 200': (r) => r.status === 200,
    'login returns access_token': (r) => safeJson(r).access_token !== undefined,
  });
  if (!ok) {
    errorRate.add(1);
    return null;
  }
  return safeJson(res).access_token;
}

// 刷新 Token（POST /auth/refresh）
export function refreshToken(token) {
  const res = http.post(`${BASE_URL}/auth/refresh`, '{}', authHeaders(token));
  return check(res, { 'refresh status 200': (r) => r.status === 200 });
}

// 获取当前用户（GET /users/me）
export function currentUser(token) {
  const res = http.get(`${BASE_URL}/users/me`, authHeaders(token));
  return check(res, { 'me status 200': (r) => r.status === 200 });
}

// 登出（POST /auth/logout）
export function logout(token) {
  const res = http.post(`${BASE_URL}/auth/logout`, '{}', authHeaders(token));
  return check(res, {
    'logout status 200/204': (r) => r.status === 200 || r.status === 204,
  });
}