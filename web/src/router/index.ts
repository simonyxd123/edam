/**
 * Vue Router 配置（v3.2 V-1 RBAC：路由级别权限校验）
 */
import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useUserStore } from '@/stores/user';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { permission: 'dashboard:read' },
      },
      {
        path: 'videos',
        name: 'Videos',
        component: () => import('@/views/Videos.vue'),
        meta: { permission: 'video:read' },
      },
      {
        path: 'videos/:id',
        name: 'VideoDetail',
        component: () => import('@/views/VideoDetail.vue'),
        meta: { permission: 'video:read' },
      },
      {
        path: 'documents',
        name: 'Documents',
        component: () => import('@/views/Documents.vue'),
        meta: { permission: 'document:read' },
      },
      {
        path: 'distribution',
        name: 'Distribution',
        component: () => import('@/views/Distribution.vue'),
        meta: { permission: 'distribution:read' },
      },
      {
        path: 'watermark',
        name: 'Watermark',
        component: () => import('@/views/Watermark.vue'),
        meta: { permission: 'watermark:read' },
      },
      {
        path: 'audit',
        name: 'Audit',
        component: () => import('@/views/Audit.vue'),
        meta: { permission: 'audit:read' },
      },
      {
        path: 'rbac',
        name: 'Rbac',
        component: () => import('@/views/RbacManagement.vue'),
        meta: { permission: 'role:read' },
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/Settings.vue'),
        // 「设置」页对**所有登录用户**开放：里面只有「个人信息 / 修改密码」
        // 两个 Tab，普通员工也需要改自己的密码（doc/普通员工使用手册 §8.2）。
        // 原来这里挂着 system:read ⇒ 普通员工根本进不去，菜单也不显示，
        // 只能找管理员改密码。系统配置 Tab 由组件内部按 system:read 单独收口。
      },
    ],
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

// 鉴权守卫（v3.2 V-1 RBAC）
router.beforeEach((to, _from, next) => {
  const userStore = useUserStore();

  // 1. 已登录访问 /login → 跳 Dashboard
  if (to.name === 'Login' && userStore.isLoggedIn) {
    return next({ name: 'Dashboard' });
  }

  // 2. 未登录访问需登录的路径 → 跳 Login
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    return next({ name: 'Login', query: { redirect: to.fullPath } });
  }

  // 3. 需登录但 user 未加载（首次启动或 token 异常）→ 拉一次
  const requiredPerm = to.meta.permission as string | undefined;
  if (requiredPerm && !userStore.user) {
    // fetchMe 失败时会 reject（store 里已改）→ 走 catch 去 Login。
    // 原来 fetchMe 内部 catch 后只调 logout() 就正常返回，Promise 永远
    // resolve → then 分支里 user 仍为 null → hasPermission 恒 false →
    // next({name:'Dashboard'}) → Dashboard 也有 permission 要求 →
    // 再次进入本守卫 → **无限重定向**直到栈溢出 / 白屏。
    return userStore.fetchMe()
      .then(() => {
        if (!userStore.hasPermission(requiredPerm)) {
          ElMessage.error('无权限访问该页面');
          return next({ name: 'Dashboard' });
        }
        next();
      })
      .catch(() => next({ name: 'Login', query: { redirect: to.fullPath } }));
  }

  // 4. 有 permission 要求但没权限 → 跳 Dashboard
  if (requiredPerm && !userStore.hasPermission(requiredPerm)) {
    ElMessage.error('无权限访问该页面');
    return next({ name: 'Dashboard' });
  }

  next();
});

export default router;