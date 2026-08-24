<script setup lang="ts">
import { ref, onMounted } from 'vue';
import axios from 'axios';

interface AuditLog {
  id: number;
  user_id: number;
  employee_no: string;
  operation_type: string;
  resource_type: string;
  resource_id: number;
  ip_address: string;
  timestamp: string;
  result: 'success' | 'denied';
}

const logs = ref<AuditLog[]>([]);
const loading = ref(false);
const filterType = ref<string>('');
const filterResult = ref<string>('');
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

const opTypes = ['login', 'logout', 'view', 'download', 'upload', 'delete', 'permission_change', 'config_change'];

async function loadLogs() {
  loading.value = true;
  try {
    const params: any = { page: page.value, page_size: pageSize.value };
    if (filterType.value) params.operation_type = filterType.value;
    if (filterResult.value) params.result = filterResult.value;
    const r = await axios.get('/api/v1/audit/logs', { params });
    logs.value = r.data.items || [];
    total.value = r.data.total || 0;
  } catch (e) {
    // dev 模式无后端时给点 mock
    logs.value = Array.from({ length: 12 }).map((_, i) => ({
      id: i + 1,
      user_id: 100 + i,
      employee_no: `E${1000 + i}`,
      operation_type: opTypes[i % opTypes.length],
      resource_type: 'video',
      resource_id: 200 + i,
      ip_address: `192.168.1.${10 + i}`,
      timestamp: new Date(Date.now() - i * 3600_000).toISOString(),
      result: i % 5 === 0 ? 'denied' : 'success',
    }));
    total.value = 12;
  } finally {
    loading.value = false;
  }
}

function colorOfResult(r: string) { return r === 'success' ? 'success' : 'danger'; }

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
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadLogs">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="logs" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="employee_no" label="工号" width="100" />
      <el-table-column prop="operation_type" label="操作" width="160" />
      <el-table-column prop="resource_type" label="资源" width="80" />
      <el-table-column prop="resource_id" label="资源ID" width="80" />
      <el-table-column prop="ip_address" label="IP" width="120" />
      <el-table-column prop="timestamp" label="时间" />
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag :type="colorOfResult(row.result)" size="small">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
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