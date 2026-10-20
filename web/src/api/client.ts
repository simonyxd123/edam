/**
 * API 客户端
 * 基于 axios + JWT 自动注入
 */
import axios, { type AxiosInstance, type AxiosError } from 'axios';
import { ElMessage } from 'element-plus';

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
      (error: AxiosError) => {
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

        // 简化：任何 /api/ 路径错误（无 response 或 4xx/5xx 401/403）都视作会话失效
        const url = error.config?.url || '';
        const isApiCall = url.startsWith('/api/') || url.includes('/api/');
        const isPublic = url.includes('/auth/login') || url.includes('/auth/refresh');
        const isSessionInvalid = isApiCall && !isPublic && (
          status === 401 ||
          status === undefined ||
          status === 403 ||
          (typeof status === 'number' && status >= 400 && status < 500)
        );

        if (isUploadIdleAbort) {
          ElMessage.error(`上传超过 ${UPLOAD_IDLE_TIMEOUT_MS / 60000} 分钟无进展，已中止，请检查网络后重试`);
        } else if (isTimeout) {
          ElMessage.error('请求超时，请检查网络后重试');
        } else if (isCanceled) {
          // 用户主动取消（关弹窗 / 离开页面），不打扰
        } else if (isSessionInvalid) {
          console.warn('[client] session invalid, status=', status, 'url=', error.config?.url);
          // 清 localStorage 三个 key
          localStorage.removeItem('access_token');
          localStorage.removeItem('refresh_token');
          localStorage.removeItem('user_id');
          if (window.location.pathname !== '/login') {
            ElMessage.warning('会话已失效，请重新登录');
            // 1.5s 后跳登录页（让用户看清提示）
            setTimeout(() => { window.location.href = '/login'; }, 1500);
          }
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

  async get<T = any>(url: string, params?: any): Promise<T> {
    return (await this.instance.get(url, { params })).data;
  }

  async post<T = any>(url: string, data?: any): Promise<T> {
    return (await this.instance.post(url, data)).data;
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