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
        meta: { permission: 'system:read' },
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
    return userStore.fetchMe().then(() => {
      if (!userStore.hasPermission(requiredPerm)) {
        ElMessage.error('无权限访问该页面');
        return next({ name: 'Dashboard' });
      }
      next();
    }).catch(() => next({ name: 'Login' }));
  }

  // 4. 有 permission 要求但没权限 → 跳 Dashboard
  if (requiredPerm && !userStore.hasPermission(requiredPerm)) {
    ElMessage.error('无权限访问该页面');
    return next({ name: 'Dashboard' });
  }

  next();
});

export default router;