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
 * 上传接口超时（毫秒）。
 *
 * 实例级 timeout 对上传是致命的：XHR 到点 abort 会直接断开 TCP，
 * 而后端 Tomcat 此时还在读 multipart 请求体，于是撞上 EOFException
 * （表现为 `MultipartException: Failed to parse multipart servlet request`）。
 * 前端允许 2GB、后端 max-file-size 也是 2GB，30s 根本传不完。
 *
 * 这里按文件大小动态估算：最慢 500KB/s 兜底，下限 30 分钟，上限 2 小时。
 */
const UPLOAD_TIMEOUT_MIN_MS = 30 * 60 * 1000; // 30 分钟
const UPLOAD_TIMEOUT_MAX_MS = 2 * 60 * 60 * 1000; // 2 小时
const UPLOAD_ASSUMED_BYTES_PER_MS = (500 * 1024) / 1000; // 500 KB/s

function uploadTimeoutFor(sizeBytes?: number): number {
  if (!sizeBytes || sizeBytes <= 0) return UPLOAD_TIMEOUT_MAX_MS;
  const estimated = Math.ceil(sizeBytes / UPLOAD_ASSUMED_BYTES_PER_MS);
  return Math.min(UPLOAD_TIMEOUT_MAX_MS, Math.max(UPLOAD_TIMEOUT_MIN_MS, estimated));
}

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

        if (isTimeout) {
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
   * - 超时按文件大小动态放宽（见 uploadTimeoutFor），避免大文件被 30s 掐断
   * - onUploadProgress 在 axios 0.27+ 上传进度事件中触发（load/total 是字节数）
   */
  async upload<T = any>(
    url: string,
    formData: FormData,
    onUploadProgress?: (loaded: number, total: number) => void,
  ): Promise<T> {
    // 从 FormData 取回原始 File 以获得真实大小（其他表单字段忽略）
    const filePart = formData.get('file');
    const size = filePart instanceof File ? filePart.size : undefined;

    return (
      await this.instance.post(url, formData, {
        timeout: uploadTimeoutFor(size),
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (e) => {
          if (onUploadProgress && e.total) {
            onUploadProgress(e.loaded, e.total);
          }
        },
      })
    ).data;
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