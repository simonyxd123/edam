/**
 * API 客户端
 * 基于 axios + JWT 自动注入
 */
import axios, { type AxiosInstance, type AxiosError, type AxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';
import { handleSessionInvalid, isApiCall, isPublicEndpoint, beginSessionRecovery, endSessionRecovery } from '@/utils/session';

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1';

/**
 * 普通接口超时（毫秒）：列表 / 详情 / 删除这类秒回的请求
 */
const DEFAULT_TIMEOUT_MS = 30000;

/**
 * 上传「无进展」超时（毫秒）。
 *
 * 不用固定总时长：8GB 视频按最慢 500KB/s 算要 4.7 小时，
 * 无论把总时长设成多少，慢链路都会被误杀，而真正的卡死又只能干等。
 *
 * 改为「连续 N 分钟收不到任何字节才中止」——每次 onUploadProgress 都重置
 * 计时器。慢但在传就一直放行；网线拔了 / 服务端挂了才报错。
 *
 * abort 会直接断开 TCP，后端 Tomcat 此时还在读 multipart 请求体会撞
 * EOFException，所以这个值不宜过短，留了 5 分钟余量。
 */
const UPLOAD_IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 分钟无进展即中止

class ApiClient {
  private instance: AxiosInstance;

  /**
   * 正在进行的 refresh 请求（单例锁）。
   *
   * <p>并发请求同时收到 401 时，只有第一个会真正发 refresh 请求，
   * 其余等待同一个 Promise —— 否则 refresh_token 会被并发消费，
   * 后到的那些拿到 401 → 把用户登出。
   */
  private refreshPromise: Promise<boolean> | null = null;


  constructor() {
    this.instance = axios.create({
      baseURL: API_BASE,
      timeout: DEFAULT_TIMEOUT_MS,
      headers: { 'Content-Type': 'application/json' },
    });

    // 请求拦截：注入 JWT
    this.instance.interceptors.request.use((config) => {
      const token = localStorage.getItem('access_token');
      const userId = localStorage.getItem('user_id');
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      if (userId) {
        config.headers['X-User-Id'] = userId;
      }
      return config;
    });

    // 响应拦截：统一错误处理
    this.instance.interceptors.response.use(
      (response) => response,
      async (error: AxiosError) => {
        const status = error.response?.status;
        const data = error.response?.data as any;

        // 超时 / 主动取消：没有 response，status 为 undefined。
        // 这类情况必须排在「会话失效」判断之前 —— 它们不代表 token 过期，
        // 否则上传超时会把用户直接踢回登录页。
        const isTimeout = error.code === 'ECONNABORTED'
          || error.code === 'ETIMEDOUT'
          || /timeout/i.test(error.message || '');
        const isCanceled = axios.isCancel(error) || error.code === 'ERR_CANCELED';
        // 我们自己触发的「上传无进展超时」也表现为 cancel，但必须提示用户，
        // 否则页面会毫无反应。靠 upload() 在 signal 上打的标记区分。
        const signal = error.config?.signal as { __idleTimeout?: boolean } | undefined;
        const isUploadIdleAbort = !!signal?.__idleTimeout && isCanceled;

        // 会话失效判定：只认 401（+ 真正的网络无响应）。
        //
        // 原来写成 `status >= 400 && status < 500` —— 那样 403 权限不足、
        // 404 资源不存在、409 冲突全都会被当成"会话失效"→ 清 token + 跳登录页。
        // 删一个不存在的视频返回 404，用户被登出。
        //
        // 另外 `error.config.url` 是**相对路径**（如 '/videos'），
        // 完整 URL 由 baseURL 在 adapter 层拼接 —— 判断 API 路径时必须
        // 把 baseURL 算进去，否则 isApiCall 恒为 false（本段逻辑形同虚设）。
        const url = error.config?.url || '';
        const baseURL = error.config?.baseURL || '';
        const isApi = isApiCall(url, baseURL);
        const isPublic = isPublicEndpoint(url);
        // 无 response 且非取消/超时 = 网络断了，不是会话失效
        const isNetworkDown = status === undefined && !isTimeout && !isCanceled;
        const isSessionInvalid = isApi && !isPublic && (status === 401 || isNetworkDown);

        // ---- 401：先试 refresh，失败才算会话失效 ----
        //
        // access_token 只有 10 分钟（AuthService.expires_in=600），
        // 而 refresh_token 此前**从未被使用过** → 用户连续操作 10 分钟后
        // 下一个请求 401 → 被踢回登录页，未保存的编辑状态全丢。
        //
        // 现在：401 → 用 refresh_token 换新 token → 重放原请求。
        // 单例锁保证并发请求只触发一次 refresh（否则 N 个并发 401 会打 N 次
        // refresh，后 N-1 次因 refresh_token 已被消费而失败）。
        // 本次请求本身就是 refresh 调用 → 不能再触发 refresh（否则死循环）
        const isRefreshCall = (error.config as any).__isRefreshCall === true;
        if (status === 401 && isApi && !isPublic && !isRefreshCall) {
          // 登记"正在续期"：main.ts 的全局 fetch / XHR 包装器看到 401 就会
          // 直接清 token 跳登录页。它若比这里先跑，refresh_token 已经被清掉，
          // 续期就无从谈起 —— 静默续期会退化成"每 10 分钟被踢回登录页"。
          beginSessionRecovery();
          try {
            const retried = await this.tryRefreshAndReplay(error);
            if (retried) {
              return retried;
            }
          } finally {
            endSessionRecovery();
          }
        }

        if (isUploadIdleAbort) {
          ElMessage.error(`上传超过 ${UPLOAD_IDLE_TIMEOUT_MS / 60000} 分钟无进展，已中止，请检查网络后重试`);
        } else if (isTimeout) {
          ElMessage.error('请求超时，请检查网络后重试');
        } else if (isCanceled) {
          // 用户主动取消（关弹窗 / 离开页面），不打扰
        } else if (isSessionInvalid) {
          console.warn('[client] session invalid, status=', status, 'url=', error.config?.url);
          // 统一走 utils/session 的处理（清 token + 提示 + 跳登录页）
          handleSessionInvalid(`client:${status ?? 'network'}`);
        } else if (status === 403) {
          // 权限不足 —— 不是会话问题，别把人登出
          ElMessage.error('没有权限执行该操作');
        } else if (status === 423) {
          ElMessage.error('账号已锁定');
        } else if (status === 429) {
          ElMessage.warning('请求过于频繁，请稍后再试');
        } else if (status && status >= 500) {
          ElMessage.error(`服务错误：${data?.title || 'Internal Server Error'}`);
        } else {
          ElMessage.error(data?.detail || data?.title || '请求失败');
        }

        // 标记已提示过，供调用方（View 的 catch）避免重复弹窗
        (error as any).__handled = true;
        return Promise.reject(error);
      }
    );
  }

  async get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
    return (await this.instance.get(url, { params, ...config })).data;
  }

  /**
   * 用 refresh_token 换新的 access_token，成功后重放原请求。
   *
   * @param error 触发本次重试的 401 错误
   * @returns 重放成功返回 Response；失败返回 null（走原有的会话失效处理）
   */
  private async tryRefreshAndReplay(error: AxiosError): Promise<any | null> {
    const refreshToken = localStorage.getItem('refresh_token');
    if (!refreshToken) {
      return null;
    }

    // 单例锁：并发 401 只触发一次 refresh
    if (!this.refreshPromise) {
      this.refreshPromise = this.doRefresh(refreshToken).finally(() => {
        this.refreshPromise = null;
      });
    }
    const ok = await this.refreshPromise;
    if (!ok) {
      return null;
    }

    // 重放原请求：带上新的 Authorization
    const config = error.config;
    if (!config) {
      return null;
    }
    const token = localStorage.getItem('access_token');
    if (token) {
      config.headers = config.headers ?? {};
      (config.headers as any).Authorization = `Bearer ${token}`;
    }
    try {
      return await this.instance.request(config);
    } catch (e) {
      // 重放仍失败 → 交给外层的会话失效处理
      return null;
    }
  }

  /**
   * 调 /auth/refresh 换 token。
   */
  private async doRefresh(refreshToken: string): Promise<boolean> {
    try {
      // 用裸 axios（不走本实例的拦截器，避免 401 时再次触发 refresh → 死循环）
      const resp = await axios.post<{
        access_token: string;
        refresh_token?: string;
      }>(
        `${API_BASE}/auth/refresh`,
        { refresh_token: refreshToken },
        { headers: { 'Content-Type': 'application/json' } },
      );
      const data = resp.data;
      if (!data?.access_token) {
        return false;
      }
      localStorage.setItem('access_token', data.access_token);
      // 后端刷新响应**不含** refresh_token（AuthService.refresh 只返新的
      // access_token），所以保留旧的
      if (data.refresh_token) {
        localStorage.setItem('refresh_token', data.refresh_token);
      }
      return true;
    } catch (e) {
      console.warn('[client] refresh failed:', e);
      return false;
    }
  }

  /**
   * POST。
   *
   * <p>`config` 形参以前不存在，调用方（如 api/video.ts 的 upload）为了传
   * multipart 的 Content-Type 只能写 `as any` 把 config 塞到第三参 ——
   * 而那个 config **被整个丢弃**，请求就带着默认的
   * `Content-Type: application/json` 发出去。
   */
  async post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return (await this.instance.post(url, data, config)).data;
  }

  /**
   * multipart/form-data 上传（带真实进度）
   * - 自动复用 JWT / X-User-Id 拦截器
   * - 超时策略为「无进展超时」：连续 5 分钟收不到字节才中止（见 UPLOAD_IDLE_TIMEOUT_MS），
   *   这样 8GB 慢链路能慢慢传完，同时卡死的连接 5 分钟内就会报错
   * - onUploadProgress 在 axios 上传进度事件中触发（load/total 是字节数）
   */
  async upload<T = any>(
    url: string,
    formData: FormData,
    onUploadProgress?: (loaded: number, total: number) => void,
  ): Promise<T> {
    const controller = new AbortController();
    let idleTimer: ReturnType<typeof setTimeout> | undefined;

    // 在 signal 上打标记，好让响应拦截器把「我们主动触发的无进展超时」
    // 与「用户主动取消」区分开：前者要提示用户，后者才静默
    const signal = controller.signal as AbortSignal & { __idleTimeout?: boolean };
    signal.__idleTimeout = true;

    // 每收到一次进度就重新计时
    const resetIdleTimer = () => {
      if (idleTimer !== undefined) clearTimeout(idleTimer);
      idleTimer = setTimeout(() => {
        controller.abort();
      }, UPLOAD_IDLE_TIMEOUT_MS);
    };
    resetIdleTimer();

    try {
      return (
        await this.instance.post(url, formData, {
          signal,
          headers: { 'Content-Type': 'multipart/form-data' },
          onUploadProgress: (e) => {
            resetIdleTimer();
            if (onUploadProgress && e.total) {
              onUploadProgress(e.loaded, e.total);
            }
          },
        })
      ).data;
    } finally {
      // 无论成功 / 失败 / 被中止，都要清掉计时器，否则会一直挂着直到超时才释放
      if (idleTimer !== undefined) clearTimeout(idleTimer);
    }
  }

  async put<T = any>(url: string, data?: any): Promise<T> {
    return (await this.instance.put(url, data)).data;
  }

  async delete<T = any>(url: string): Promise<T> {
    return (await this.instance.delete(url)).data;
  }
}

export const api = new ApiClient();
export const apiBase = API_BASE;