<script setup lang="ts">
import { computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useUserStore } from '@/stores/user';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const activeMenu = computed(() => route.path);

const menuItems = [
  { path: '/', icon: 'Odometer', label: '仪表板' },
  { path: '/videos', icon: 'VideoCamera', label: '视频' },
  { path: '/documents', icon: 'Document', label: '文档' },
  { path: '/distribution', icon: 'Promotion', label: '外发审批' },
  { path: '/watermark', icon: 'Brush', label: '水印溯源' },
  { path: '/audit', icon: 'Tickets', label: '审计日志' },
  { path: '/settings', icon: 'Setting', label: '设置' },
];

function handleLogout() {
  userStore.logout();
  router.push('/login');
}
</script>

<template>
  <el-container class="main-layout">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <h2>EDAM</h2>
        <p>数字资产防护</p>
      </div>
      <el-menu :default-active="activeMenu" router>
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ route.meta.title || route.name }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown>
            <span class="user-info">
              <el-avatar :size="32">{{ userStore.user?.real_name?.[0] || 'U' }}</el-avatar>
              <span class="username">{{ userStore.user?.real_name || '未登录' }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/settings')">个人设置</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
}
.sidebar {
  background: #001529;
  color: #fff;
  .logo {
    padding: 20px;
    text-align: center;
    h2 { color: #fff; margin: 0; }
    p { color: #aaa; font-size: 12px; margin: 4px 0 0; }
  }
  :deep(.el-menu) {
    background: transparent;
    border: none;
  }
  :deep(.el-menu-item) {
    color: #ccc;
    &.is-active { color: #fff; background: #1890ff; }
  }
}
.header {
  background: #fff;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;
  justify-content: space-between;
  .user-info {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
  }
}
.content {
  background: #f0f2f5;
  padding: 20px;
}
</style>