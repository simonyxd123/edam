/**
 * RBAC 权限管理 API（v3.2 V-1）
 */
import { api } from './client';

// 用户列表（来自后端 /users 分页接口）
export interface User {
  id: number;
  username: string;
  employee_no: string;
  real_name?: string;
  email?: string;
  dept_id?: number;
  status: number;        // 1=active 2=disabled 3=locked
  mfa_enabled: number;
  failed_login_count: number;
  must_change_password: boolean;
  last_login_at?: string;
}

export interface UserPage {
  items: User[];
  pagination: {
    page: number;
    page_size: number;
    total: number;
    total_pages: number;
  };
}

export interface Permission {
  id: number;
  code: string;
  name: string;
  resource_type: string;
  action: string;
  description?: string;
}

export interface Role {
  id: number;
  code: string;
  name: string;
  description?: string;
  is_system: boolean;
  permissions: string[];
}

export interface UserRole {
  id: number;
  code: string;
  name: string;
}

export const rbacApi = {
  listPermissions: (resource_type?: string) =>
    api.get<Permission[]>('/rbac/permissions',
      resource_type ? { resource_type } : undefined),

  listRoles: () => api.get<Role[]>('/rbac/roles'),

  createRole: (data: { code: string; name: string; description?: string; permission_ids?: number[] }) =>
    api.post<Role>('/rbac/roles', data),

  updateRole: (id: number, data: { name?: string; description?: string; permission_ids?: number[] }) =>
    api.put<{ id: number }>(`/rbac/roles/${id}`, data),

  deleteRole: (id: number) =>
    api.delete<{ id: number; deleted: boolean }>(`/rbac/roles/${id}`),

  assignPermissionsToRole: (id: number, permission_ids: number[]) =>
    api.post<{ role_id: number; assigned_count: number }>(`/rbac/roles/${id}/permissions`, { permission_ids }),

  getUserRoles: (userId: number) =>
    api.get<UserRole[]>(`/rbac/users/${userId}/roles`),

  assignRolesToUser: (userId: number, role_ids: number[]) =>
    api.post<{ user_id: number; assigned_count: number }>(`/rbac/users/${userId}/roles`, { role_ids }),

  listUsers: (params?: { page?: number; page_size?: number; status?: number }) =>
    api.get<UserPage>('/users', params),

  createUser: (data: {
    username: string;
    password: string;
    employee_no: string;
    real_name?: string;
    email?: string;
    dept_id?: number;
    mfa_enabled?: number;
  }) => api.post<SysUserView>('/users', data),

  updateUser: (id: number, data: {
    real_name?: string;
    email?: string;
    dept_id?: number;
    status?: 'active' | 'disabled' | 'locked';
  }) => api.put<SysUserView>(`/users/${id}`, data),

  resetPassword: (id: number, password: string) =>
    api.put<void>(`/users/${id}/password`, { password }),

  deleteUser: (id: number) =>
    api.delete<void>(`/users/${id}`),
};

// SysUserView 类型（与后端 SysUserView DTO 对齐）
export interface SysUserView {
  id: number;
  username: string;
  employee_no: string;
  real_name?: string;
  email?: string;
  dept_id?: number;
  status: number;
  mfa_enabled: number;
  failed_login_count?: number;
  must_change_password?: boolean;
  last_login_at?: string;
  created_at?: string;
  updated_at?: string;
}
