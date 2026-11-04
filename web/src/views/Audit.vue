<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { api } from '@/api/client';
import {
  AUDIT_OP_TYPES,
  auditOpLabel,
  auditResourceLabel,
  auditResultLabel,
} from '@/constants/audit';

interface AuditLog {
  id: number;
  user_id: number | null;
  employee_no: string;
  operation_type: string;
  resource_type: string;
  resource_id: number | null;
  ip_address: string;
  user_agent?: string;
  http_method?: string;
  endpoint?: string;
  status_code?: number | null;
  duration_ms?: number | null;
  session_id?: string;
  timestamp: string;
  result: 'success' | 'denied' | 'failure';
  detail?: string;
}

const logs = ref<AuditLog[]>([]);
const loading = ref(false);
const filterType = ref<string>('');
const filterResult = ref<string>('');
const filterResourceType = ref<string>('');
const filterDateRange = ref<[string, string] | null>(null);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

/**
 * 操作类型下拉的数据源。
 *
 * <p>优先用后端返回的清单（{@code GET /audit/logs/operation-types}）——
 * 后端才是权威，后端加了新类型这里自动跟上；
 * 拿不到时退回本地常量（{@code AUDIT_OP_TYPES}），下拉框不至于空白。
 */
const opTypes = ref<string[]>([...AUDIT_OP_TYPES]);
/** 资源类型下拉的数据源（从库里 DISTINCT，不写死） */
const resourceTypes = ref<string[]>([]);

interface PageResp<T> {
  items: T[];
  pagination: { page: number; page_size: number; total: number; total_pages: number };
}

async function loadFilterOptions() {
  // 两个清单都是"锦上添花"，失败了就用本地常量，不能因此让整页报错
  try {
    const resp = await api.get<{ operation_types: string[] }>('/audit/logs/operation-types');
    if (resp?.operation_types?.length) {
      opTypes.value = resp.operation_types;
    }
  } catch {
    /* 保持本地默认值 */
  }
  try {
    const resp = await api.get<{ resource_types: string[] }>('/audit/logs/resource-types');
    resourceTypes.value = resp?.resource_types || [];
  } catch {
    resourceTypes.value = [];
  }
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
    if (filterResourceType.value) params.resource_type = filterResourceType.value;
    // 后端一直支持 start_time / end_time（yyyy-MM-dd HH:mm:ss），之前前端没传，
    // 于是"按时间查"这个功能事实上不存在
    if (filterDateRange.value) {
      params.start_time = filterDateRange.value[0];
      params.end_time = filterDateRange.value[1];
    }

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
  filterResourceType.value = '';
  filterDateRange.value = null;
  page.value = 1;
  loadLogs();
}

onMounted(() => {
  loadLogs();
  loadFilterOptions();
});
</script>

<template>
  <div class="audit">
    <h2>审计日志</h2>

    <el-form :inline="true" style="margin-bottom:16px">
      <el-form-item label="操作类型">
        <el-select v-model="filterType" clearable placeholder="全部" style="width:180px">
          <el-option
            v-for="t in opTypes"
            :key="t"
            :label="auditOpLabel(t)"
            :value="t"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="结果">
        <el-select v-model="filterResult" clearable placeholder="全部" style="width:110px">
          <el-option label="成功" value="success" />
          <el-option label="拒绝" value="denied" />
          <el-option label="失败" value="failure" />
        </el-select>
      </el-form-item>
      <el-form-item label="资源类型">
        <el-select
          v-model="filterResourceType"
          clearable
          placeholder="全部"
          style="width:160px"
          filterable
        >
          <el-option
            v-for="rt in resourceTypes"
            :key="rt"
            :label="auditResourceLabel(rt)"
            :value="rt"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="filterDateRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始"
          end-placeholder="结束"
          style="width:340px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadLogs">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="logs" v-loading="loading" stripe empty-text="暂无记录">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="工号" width="100">
        <template #default="{ row }">
          {{ row.employee_no || (row.user_id ? `用户#${row.user_id}` : '未知') }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          {{ auditOpLabel(row.operation_type) }}
        </template>
      </el-table-column>
      <el-table-column label="资源" width="110">
        <template #default="{ row }">
          <span>{{ auditResourceLabel(row.resource_type) }}</span>
          <span v-if="row.resource_id" class="rid">#{{ row.resource_id }}</span>
        </template>
      </el-table-column>
      <el-table-column label="接口" width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.http_method" class="method">{{ row.http_method }}</span>
          <span>{{ row.endpoint || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态码" width="80">
        <template #default="{ row }">
          <el-tag
            v-if="row.status_code"
            :type="row.status_code < 400 ? 'info' : 'danger'"
            size="small"
          >
            {{ row.status_code }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">
          {{ row.duration_ms != null ? `${row.duration_ms}ms` : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="ip_address" label="IP" width="130" />
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">
          {{ $fmt(row.timestamp) }}
        </template>
      </el-table-column>
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag :type="colorOfResult(row.result)" size="small">
            {{ auditResultLabel(row.result) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
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
.rid { color: #909399; margin-left: 2px; }
.method {
  display: inline-block;
  margin-right: 6px;
  padding: 0 5px;
  border-radius: 3px;
  background: #f0f2f5;
  color: #606266;
  font-family: monospace;
  font-size: 12px;
}
</style>
