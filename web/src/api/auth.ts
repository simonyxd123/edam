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
  dept_id: number;
  dept_name: string;
  roles: string[];
  permissions: string[];
  last_login_time: string;
}

export const authApi = {
  login: (data: LoginRequest) => api.post<LoginResponse>('/auth/login', data),
  refresh: (refresh_token: string) => api.post<LoginResponse>('/auth/refresh', { refresh_token }),
  logout: () => api.post<void>('/auth/logout'),
  me: () => api.get<CurrentUser>('/auth/me'),
};