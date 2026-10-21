/**
 * 鉴权相关 API
 */
import { api } from './client';

export interface LoginRequest {
  employee_no: string;
  password: string;
  mfa_code?: string;
}

export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
}

export interface CurrentUser {
  user_id: number;
  employee_no: string;
  real_name: string;
  email?: string;
  phone?: string;
  dept_id: number;
  dept_name?: string;
  status: number;
  roles: string[];
  permissions: string[];
  /** 后端字段名是 last_login_at（不是 last_login_time） */
  last_login_at?: string;
}

export const authApi = {
  login: (data: LoginRequest) => api.post<LoginResponse>('/auth/login', data),
  refresh: (refresh_token: string) => api.post<LoginResponse>('/auth/refresh', { refresh_token }),
  /**
   * 登出。
   *
   * <p>必须带 refresh_token —— 后端只有在拿到它时才会真正吊销会话
   * （AuthController：`if (request.getRefreshToken() != null) authService.logout(...)`）。
   * 只传 user_id 的话服务端会话记录不删，JWT 在有效期内依然可用。
   */
  logout: (refreshToken?: string | null, userId?: number) =>
    api.post<void>('/auth/logout', { refresh_token: refreshToken, user_id: userId }),
  me: () => api.get<CurrentUser>('/auth/me'),
  updateProfile: (data: { email?: string; phone?: string; real_name?: string }) =>
    api.put<CurrentUser>('/auth/profile', data),
  changePassword: (old_password: string, new_password: string) =>
    api.post<{ changed: boolean }>('/auth/change-password', { old_password, new_password }),
};