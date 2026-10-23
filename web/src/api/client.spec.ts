/**
 * api 客户端的会话续期（refresh）流程单测。
 *
 * 这个逻辑最容易静默坏掉：
 *   - 并发 401 时如果不做单例锁，refresh_token 会被并发消费，
 *     后到的那些拿到 401 → 把用户登出
 *   - refresh 自身返回 401 时如果又触发 refresh → 无限递归
 *   - 重放原请求时如果没换 Authorization → 再 401，死循环
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// vi.mock 的工厂会被提升到文件顶部，不能引用普通顶层变量 —— 用 hoisted 包一层
const h = vi.hoisted(() => {
  const mockInstance = {
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() },
    },
    get: vi.fn(),
    post: vi.fn(),
    request: vi.fn(),
  };
  const postMock = vi.fn();
  const createMock = vi.fn(() => mockInstance);
  return { mockInstance, postMock, createMock };
});

vi.mock('axios', () => {
  const axios: any = vi.fn();
  axios.create = h.createMock;
  axios.post = h.postMock;
  axios.isCancel = () => false;
  return { default: axios, ...axios };
});

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}));

vi.mock('@/utils/session', () => ({
  handleSessionInvalid: vi.fn(),
  // 与真实实现一致：baseURL + url 拼起来判断（axios 的 config.url 是相对路径）
  isApiCall: (url: string, baseURL?: string) =>
    !!(baseURL || url) && `${baseURL ?? ''}${url ?? ''}`.includes('/api/'),
  isPublicEndpoint: (url: string) => !!url && (url.includes('/auth/login') || url.includes('/auth/refresh')),
}));

// 静态导入以触发模块加载（axios.create 被 mock → 拦截器注册在 mockInstance 上）
import { api } from './client';
void api;

/**
 * 从 mockInstance 上取出注册好的响应错误拦截器。
 *
 * <p>必须在模块加载时**一次性捕获**：beforeEach 里的 mockClear()
 * 会把 use() 的调用记录也清掉，之后就取不到了。
 */
const responseErrorHandler: (e: any) => Promise<any> = (() => {
  const calls = h.mockInstance.interceptors.response.use.mock.calls;
  return calls[calls.length - 1][1];
})();

function make401() {
  return {
    response: { status: 401, data: {} },
    config: { url: '/videos', baseURL: '/api/v1', headers: {} },
    isAxiosError: true,
  } as any;
}

describe('api client 的 refresh 流程', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('access_token', 'old-access');
    localStorage.setItem('refresh_token', 'my-refresh');
    h.mockInstance.interceptors.response.use.mockClear();
    h.mockInstance.request.mockReset();
    h.postMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('401 时先用 refresh_token 换新 token 并重放原请求', async () => {
    h.postMock.mockResolvedValueOnce({ data: { access_token: 'new-access' } });
    const replayed = { data: { ok: true }, status: 200 };
    h.mockInstance.request.mockResolvedValueOnce(replayed);

    const handler = responseErrorHandler;
    const result = await handler(make401());

    // refresh 被调用，且带上了正确的 refresh_token
    expect(h.postMock).toHaveBeenCalledTimes(1);
    expect(h.postMock.mock.calls[0][0]).toContain('/auth/refresh');
    expect(h.postMock.mock.calls[0][1]).toEqual({ refresh_token: 'my-refresh' });

    // 新 token 已写入 localStorage
    expect(localStorage.getItem('access_token')).toBe('new-access');
    // 后端刷新响应不含 refresh_token → 旧的必须保留
    expect(localStorage.getItem('refresh_token')).toBe('my-refresh');

    // 原请求被重放，且带上了新 token
    expect(h.mockInstance.request).toHaveBeenCalledTimes(1);
    const replayConfig = h.mockInstance.request.mock.calls[0][0];
    expect(replayConfig.headers.Authorization).toBe('Bearer new-access');

    // 返回重放结果（而不是 reject）
    expect(result).toBe(replayed);
  });

  it('并发 401 只触发一次 refresh（单例锁）', async () => {
    let resolveRefresh: (v: any) => void = () => {};
    h.postMock.mockImplementationOnce(() => new Promise((res) => { resolveRefresh = res; }));
    h.mockInstance.request.mockResolvedValue({ data: { ok: true } });

    const handler = responseErrorHandler;

    // 三个并发 401
    const p1 = handler(make401());
    const p2 = handler(make401());
    const p3 = handler(make401());

    // refresh 还没 resolve，此时只应有一个 refresh 请求在飞
    expect(h.postMock).toHaveBeenCalledTimes(1);

    resolveRefresh({ data: { access_token: 'new-access' } });
    await Promise.all([p1, p2, p3]);

    // 仍然只有一次 refresh
    expect(h.postMock).toHaveBeenCalledTimes(1);
  });

  it('refresh 失败时拒绝该请求（并走会话失效处理），且不重放', async () => {
    h.postMock.mockRejectedValueOnce(new Error('refresh_token 无效或已过期'));

    const handler = responseErrorHandler;
    // refresh 失败 → 会话确实失效 → 拦截器继续往下走 → reject 原错误
    await expect(handler(make401())).rejects.toBeTruthy();
    // 原请求没有被重放
    expect(h.mockInstance.request).not.toHaveBeenCalled();
  });

  it('没有 refresh_token 时不尝试 refresh，直接拒绝', async () => {
    localStorage.removeItem('refresh_token');

    const handler = responseErrorHandler;
    await expect(handler(make401())).rejects.toBeTruthy();
    expect(h.postMock).not.toHaveBeenCalled();
  });
});
