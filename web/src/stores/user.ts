/**
 * 用户状态管理（v3.2 V-1 RBAC：roles + permissions 已落 CurrentUser）
 */
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { authApi, type CurrentUser } from '@/api/auth';

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(localStorage.getItem('access_token'));
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'));
  const user = ref<CurrentUser | null>(null);

  const isLoggedIn = computed(() => !!token.value);

  // 角色 codes（含 admin 时也有 admin 字符串）
  const roles = computed(() => user.value?.roles ?? []);

  // 是否超级管理员（含 admin 角色 → 短路 *:* 权限）
  const isAdmin = computed(() => roles.value.includes('admin'));

  /**
   * 权限检查（带通配）
   * - 拥有 *:* → 所有权限通过
   * - 拥有 *:<action> → 该 action 所有资源通过
   * - 否则查 code 精确匹配
   */
  function hasPermission(code: string): boolean {
    if (!code) return false;
    const perms = user.value?.permissions ?? [];
    if (perms.includes('*:*')) return true;
    const colon = code.indexOf(':');
    if (colon > 0 && perms.includes('*:' + code.substring(colon + 1))) return true;
    return perms.includes(code);
  }

  async function login(employeeNo: string, password: string) {
    const resp = await authApi.login({ employee_no: employeeNo, password });
    token.value = resp.access_token;
    refreshToken.value = resp.refresh_token;
    localStorage.setItem('access_token', resp.access_token);
    localStorage.setItem('refresh_token', resp.refresh_token);
    await fetchMe();
  }

  async function fetchMe() {
    try {
      const me = await authApi.me();
      user.value = me;
      localStorage.setItem('user_id', String(me.user_id));
    } catch (e) {
      logout();
    }
  }

  function logout() {
    const uid = user.value?.user_id;
    authApi.logout(uid).finally(() => {
      token.value = null;
      refreshToken.value = null;
      user.value = null;
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      localStorage.removeItem('user_id');
    });
  }

  function tryRestore() {
    if (token.value) fetchMe();
  }

  return {
    token,
    refreshToken,
    user,
    isLoggedIn,
    roles,
    isAdmin,
    hasPermission,
    login,
    logout,
    fetchMe,
    tryRestore,
  };
});
