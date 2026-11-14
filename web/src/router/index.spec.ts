/**
 * 路由守卫的回归测试。
 *
 * <p><b>盯的是什么</b>：守卫遇到"没有权限的目标"时，落到哪里。
 *
 * <p>原来的实现一律 `next({ name: 'Dashboard' })`。如果用户连
 * {@code dashboard:read} 都没有（管理员在 RBAC 管理界面把这个权限码
 * 从某个角色上摘掉就会发生 —— 权限是数据不是代码，改错了不会编译报错），
 * 跳过去会再次进入守卫 → 再次判定无权限 → 再跳 Dashboard ……
 * Vue Router 检测到"重定向到同一个位置"会抛
 * "Detected an infinite redirection in a navigation guard"，导航直接失败，
 * 用户看到的是**白屏**，连"无权限"的提示都收不到。
 *
 * <p>这两个用例把两条落点都钉死：有仪表板权限 → 回仪表板；
 * 连仪表板都没权限 → 去"无权限"页（而不是死循环）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { createAppRouter } from '@/router';
import { useUserStore } from '@/stores/user';

// element-plus 的 ESM 入口会 import 它的 CSS —— vitest 默认把依赖外置给 Node，
// Node 不认 ".css" 扩展名 ⇒ 收集用例阶段就炸。
// 这里只 mock 守卫用到的那一个 API（ElMessage.error）。
vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}));

function loginAs(permissions: string[]) {
  const store = useUserStore();
  store.user = {
    user_id: 7,
    employee_no: 'E0007',
    permissions,
    roles: ['tester'],
  } as any;
}

describe('路由守卫 · 无权限时的落点', () => {
  beforeEach(() => {
    localStorage.clear();
    // isLoggedIn = !!token —— 必须在 store 初始化前放好
    localStorage.setItem('access_token', 'test-token');
    setActivePinia(createPinia());
  });

  it('缺目标页权限、但有 dashboard:read ⇒ 回仪表板', async () => {
    const router = createAppRouter();
    loginAs(['dashboard:read']);

    await router.push('/audit');
    await router.isReady();

    expect(router.currentRoute.value.name).toBe('Dashboard');
  });

  it('连 dashboard:read 都没有 ⇒ 落"无权限"页，**不能**回仪表板（否则无限重定向）', async () => {
    const router = createAppRouter();
    loginAs(['video:read']);   // 没有 dashboard:read，也没有 audit:read

    await router.push('/audit');
    await router.isReady();

    expect(router.currentRoute.value.name).toBe('Forbidden');
    expect(router.currentRoute.value.name).not.toBe('Dashboard');
  });

  it('有目标页权限 ⇒ 正常进入，不被守卫拦下', async () => {
    const router = createAppRouter();
    loginAs(['dashboard:read', 'audit:read']);

    await router.push('/audit');
    await router.isReady();

    expect(router.currentRoute.value.name).toBe('Audit');
  });
});
