<script setup lang="ts">
import { onMounted } from 'vue';
import { useUserStore } from '@/stores/user';

const userStore = useUserStore();

onMounted(() => {
  // 自动登录（如果 localStorage 有 token）
  // 必须**无条件**调用：原来写成 `if (!userStore.isLoggedIn) tryRestore()`，
  // 而 isLoggedIn 就是 !!token → 没 token 时进不去，
  // 有 token 时又不进 —— tryRestore 永远不执行，恢复登录态的逻辑形同虚设。
  userStore.tryRestore();
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