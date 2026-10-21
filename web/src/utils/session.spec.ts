/**
 * 会话工具单测
 *
 * 重点验证 isApiCall —— 之前 client.ts 里写成
 * `url.startsWith('/api/')`（axios 的 config.url 是**相对路径**，
 * 完整 URL 要拼 baseURL），导致判断恒为 false，
 * 整个会话失效处理形同虚设。
 */
import { describe, it, expect } from 'vitest';
import { isApiCall, isPublicEndpoint } from './session';

describe('isApiCall', () => {
  it('相对路径 + baseURL 能识别出 API 调用', () => {
    expect(isApiCall('/videos', '/api/v1')).toBe(true);
    expect(isApiCall('/documents/1/preview', '/api/v1')).toBe(true);
  });

  it('绝对 URL 也能识别', () => {
    expect(isApiCall('/api/v1/videos')).toBe(true);
    expect(isApiCall('http://host/api/v1/videos')).toBe(true);
  });

  it('非 API 路径不误判', () => {
    expect(isApiCall('/static/logo.png')).toBe(false);
    expect(isApiCall(undefined)).toBe(false);
    expect(isApiCall('')).toBe(false);
  });

  it('相对路径 + baseURL 时以拼接后的完整路径为准', () => {
    // axios 实际请求的是 /api/v1/login —— 它**是**一次 API 调用
    // （前端路由 /login 不走 axios，不会走到这里）
    expect(isApiCall('/login', '/api/v1')).toBe(true);
  });
});

describe('isPublicEndpoint', () => {
  it('登录 / 刷新端点属于公开端点', () => {
    expect(isPublicEndpoint('/auth/login')).toBe(true);
    expect(isPublicEndpoint('/auth/refresh')).toBe(true);
  });

  it('其它端点不是公开端点', () => {
    expect(isPublicEndpoint('/auth/me')).toBe(false);
    expect(isPublicEndpoint('/videos')).toBe(false);
    expect(isPublicEndpoint(undefined)).toBe(false);
  });
});
