/**
 * 用户状态管理
 */
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { authApi, type CurrentUser } from '@/api/auth';

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(localStorage.getItem('access_token'));
  const refreshToken = ref<string | null>(localStorage.getItem('refresh_token'));
  const user = ref<CurrentUser | null>(null);

  const isLoggedIn = computed(() => !!token.value);

  async function login(employeeNo: string, password: string) {
    const resp = await authApi.login({ employee_no: employeeNo, password });
    token.value = resp.access_token;
    refreshToken.value = resp.refresh_token;
    localStorage.setItem('access_token', resp.access_token);
    localStorage.setItem('refresh_token', resp.refresh_token);

    // 获取用户信息（同时获得 user_id 写入 user_id header）
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
    token.value = null;
    refreshToken.value = null;
    user.value = null;
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
    localStorage.removeItem('user_id');
  }

  function tryRestore() {
    // 启动时尝试从 localStorage 恢复 session
    if (token.value) {
      fetchMe();
    }
  }

  return {
    token,
    refreshToken,
    user,
    isLoggedIn,
    login,
    logout,
    fetchMe,
    tryRestore,
  };
});