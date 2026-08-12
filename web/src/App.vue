<script setup lang="ts">
import { onMounted } from 'vue';
import { useUserStore } from '@/stores/user';

const userStore = useUserStore();

onMounted(() => {
  // 自动登录（如果 localStorage 有 token）
  if (!userStore.isLoggedIn) {
    userStore.tryRestore();
  }
});
</script>

<template>
  <el-config-provider>
    <router-view />
  </el-config-provider>
</template>

<style lang="scss">
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
</style>