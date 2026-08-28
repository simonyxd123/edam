<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue';
import { api } from '@/api/client';
import { useUserStore } from '@/stores/user';

const userStore = useUserStore();

const stats = reactive({
  total_videos: 0,
  total_documents: 0,
  pending_approvals: 0,
  recent_logins: 0,
});

const loading = ref(false);

async function loadStats() {
  loading.value = true;
  try {
    const data = await api.get<{
      total_videos: number;
      total_documents: number;
      pending_approvals: number;
      recent_logins: number;
    }>('/dashboard/stats');
    Object.assign(stats, data);
  } catch (e: any) {
    console.error('load dashboard stats failed', e);
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  loadStats();
});
</script>

<template>
  <div class="dashboard" v-loading="loading">
    <h2 class="page-title">仪表板</h2>

    <el-row :gutter="20" class="stat-row">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon video"><VideoCamera /></el-icon>
            <div>
              <div class="stat-value">{{ stats.total_videos }}</div>
              <div class="stat-label">视频总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon doc"><Document /></el-icon>
            <div>
              <div class="stat-value">{{ stats.total_documents }}</div>
              <div class="stat-label">文档总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon approval"><Promotion /></el-icon>
            <div>
              <div class="stat-value">{{ stats.pending_approvals }}</div>
              <div class="stat-label">待审批</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon user"><User /></el-icon>
            <div>
              <div class="stat-value">{{ stats.recent_logins }}</div>
              <div class="stat-label">今日登录</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="welcome-card">
      <h3>欢迎使用 EDAM</h3>
      <p>您好，{{ userStore.user?.real_name }}！当前角色：{{ userStore.user?.roles?.join(', ') }}</p>
      <p>这是企业全格式数字资产防泄密系统 v3.1.0 终端。</p>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.dashboard {
  max-width: 1400px;
  margin: 0 auto;
}
.page-title {
  margin: 0 0 20px;
  color: #333;
}
.stat-row { margin-bottom: 20px; }
.stat-card {
  .stat-content {
    display: flex;
    align-items: center;
    gap: 16px;
  }
  .stat-icon {
    font-size: 48px;
    &.video { color: #1890ff; }
    &.doc { color: #52c41a; }
    &.approval { color: #faad14; }
    &.user { color: #722ed1; }
  }
  .stat-value {
    font-size: 32px;
    font-weight: bold;
    color: #333;
  }
  .stat-label {
    color: #999;
    font-size: 14px;
  }
}
.welcome-card {
  h3 { color: #1890ff; margin: 0 0 16px; }
  p { color: #666; line-height: 1.8; }
}
</style>
