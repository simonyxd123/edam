/**
 * API 客户端
 * 基于 axios + JWT 自动注入
 */
import axios, { type AxiosInstance, type AxiosError } from 'axios';
import { ElMessage } from 'element-plus';

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1';

class ApiClient {
  private instance: AxiosInstance;

  constructor() {
    this.instance = axios.create({
      baseURL: API_BASE,
      timeout: 30000,
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

        if (status === 401) {
          // Token 过期 / 会话失效：清 localStorage + 跳登录页
          localStorage.removeItem('access_token');
          localStorage.removeItem('refresh_token');
          localStorage.removeItem('user_id');
          if (window.location.pathname !== '/login') {
            ElMessage.warning('会话已失效，请重新登录');
            // 给用户 1.5s 看到提示再跳
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
   * - onUploadProgress 在 axios 0.27+ 上传进度事件中触发（load/total 是字节数）
   */
  async upload<T = any>(
    url: string,
    formData: FormData,
    onUploadProgress?: (loaded: number, total: number) => void,
  ): Promise<T> {
    return (
      await this.instance.post(url, formData, {
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