<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';

interface AuditLog {
  id: number;
  user_id: number;
  employee_no: string;
  operation_type: string;
  resource_type: string;
  resource_id: number;
  ip_address: string;
  timestamp: string;
  result: 'success' | 'denied' | 'failure';
  detail?: string;
}

const logs = ref<AuditLog[]>([]);
const loading = ref(false);
const filterType = ref<string>('');
const filterResult = ref<string>('');
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

const opTypes = ['login', 'logout', 'view', 'download', 'upload', 'delete', 'permission_change', 'config_change'];

interface PageResp<T> {
  items: T[];
  pagination: { page: number; page_size: number; total: number; total_pages: number };
}

async function loadLogs() {
  loading.value = true;
  try {
    const params: Record<string, any> = {
      page: page.value,
      page_size: pageSize.value,
    };
    if (filterType.value) params.operation_type = filterType.value;
    if (filterResult.value) params.result = filterResult.value;

    const resp = await api.get<PageResp<AuditLog>>('/audit/logs', params);
    logs.value = resp.items || [];
    total.value = resp.pagination?.total || 0;
  } catch (e) {
    // api 客户端已经弹过错误提示，这里只清空数据
    logs.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

function colorOfResult(r: string) {
  if (r === 'success') return 'success';
  if (r === 'denied') return 'warning';
  return 'danger';
}

function resetFilter() {
  filterType.value = '';
  filterResult.value = '';
  page.value = 1;
  loadLogs();
}

onMounted(loadLogs);
</script>

<template>
  <div class="audit">
    <h2>审计日志</h2>

    <el-form :inline="true" style="margin-bottom:16px">
      <el-form-item label="操作类型">
        <el-select v-model="filterType" clearable placeholder="全部" style="width:160px">
          <el-option v-for="t in opTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="结果">
        <el-select v-model="filterResult" clearable placeholder="全部" style="width:120px">
          <el-option label="成功" value="success" />
          <el-option label="拒绝" value="denied" />
          <el-option label="失败" value="failure" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadLogs">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="logs" v-loading="loading" stripe empty-text="暂无记录">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="employee_no" label="工号" width="100" />
      <el-table-column prop="operation_type" label="操作" width="160" />
      <el-table-column prop="resource_type" label="资源" width="100" />
      <el-table-column prop="resource_id" label="资源ID" width="80" />
      <el-table-column prop="ip_address" label="IP" width="140" />
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">
          {{ $fmt(row.timestamp) }}
        </template>
      </el-table-column>
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag :type="colorOfResult(row.result)" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip />
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :total="total"
      layout="prev, pager, next, total"
      style="margin-top:16px"
      @current-change="loadLogs"
    />
  </div>
</template>

<style scoped>
.audit { padding: 16px; }
</style>