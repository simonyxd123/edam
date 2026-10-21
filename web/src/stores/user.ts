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

  /**
   * 拉取当前用户信息。
   *
   * <p><b>失败时必须 reject</b>：原来 catch 里只调 logout() 就正常返回，
   * Promise 永远 resolve —— 路由守卫里的 `.catch(() => next({name:'Login'}))`
   * 是死代码。守卫走的是 then 分支：fetchMe 失败 → user 仍为 null →
   * hasPermission 恒 false → next({name:'Dashboard'}) → Dashboard 也有
   * permission 要求 → 再次进入守卫 → 无限重定向直到栈溢出 / 白屏。
   */
  async function fetchMe() {
    const me = await authApi.me();
    user.value = me;
    localStorage.setItem('user_id', String(me.user_id));
    return me;
  }

  /**
   * 登出。
   *
   * <p><b>必须把 refresh_token 一起发给后端</b>：后端的登出逻辑是
   * {@code if (request.getRefreshToken() != null) authService.logout(...)}，
   * 只传 user_id 时 refresh_token 为 null → 服务端会话记录不删，
   * JWT 在有效期内依然可用（前端清了 localStorage，服务端不认）。
   */
  function logout() {
    const uid = user.value?.user_id;
    const rt = refreshToken.value;
    // 先同步清本地状态，不等服务端（登出必须立即生效）
    token.value = null;
    refreshToken.value = null;
    user.value = null;
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
    localStorage.removeItem('user_id');
    // 再通知服务端吊销会话；失败也无所谓（本地已清）
    if (rt || uid) {
      authApi.logout(rt, uid).catch(() => { /* 已登出，忽略 */ });
    }
  }

  /**
   * 应用启动时尝试恢复登录态。
   *
   * <p>调用方必须**无条件**调用（原来 App.vue 写成
   * {@code if (!userStore.isLoggedIn) userStore.tryRestore()}，
   * 而 isLoggedIn 就是 !!token → 没 token 时 tryRestore 内部的
   * {@code if (token.value)} 又要求有 token → 永远不执行，逻辑死结）。
   */
  function tryRestore() {
    if (!token.value) return;
    fetchMe().catch(() => {
      // token 失效 → 走登出流程
      logout();
    });
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
