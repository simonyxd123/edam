<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useUserStore } from '@/stores/user';

const router = useRouter();
const userStore = useUserStore();

/**
 * 「无权限」落地页。
 *
 * <p>为什么需要它：路由守卫遇到没有权限的目标时，原来一律 `next({name:'Dashboard'})`。
 * 但**如果用户连 dashboard:read 都没有**，跳过去会再次进入守卫 → 再次判定无权限 →
 * 再跳 Dashboard —— Vue Router 检测到"重定向到同一个位置"会抛
 * "Detected an infinite redirection in a navigation guard"，导航直接失败，
 * 用户看到的是**白屏**，连"无权限"这三个字都看不到。
 *
 * <p>触发路径很现实：管理员在 RBAC 管理界面把 dashboard:read 从某个角色上摘掉
 * （权限是数据不是代码，改错了不会编译报错），这个角色的所有人立刻白屏。
 */
function goHome() {
  if (userStore.hasPermission('dashboard:read')) {
    router.push({ name: 'Dashboard' });
  } else {
    // 连仪表板都进不去 ⇒ 回登录页重新选身份（换账号后权限可能不同）
    router.push({ name: 'Login' });
  }
}
</script>

<template>
  <div class="forbidden">
    <el-result icon="warning" title="没有访问权限" sub-title="当前账号缺少访问该页面所需的权限码，请联系管理员分配角色。">
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.forbidden {
  padding: 48px 16px;
  text-align: center;
}
</style>
