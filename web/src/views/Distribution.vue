<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { distributionApi, type Approval } from '@/api/distribution';

const loading = ref(false);
const approvals = ref<Approval[]>([]);
const total = ref(0);
const searchForm = ref({
  status: '',
  applicant_id: undefined as number | undefined,
});

async function loadData(page = 1) {
  loading.value = true;
  try {
    const resp = await distributionApi.list({
      page,
      page_size: 20,
      status: searchForm.value.status || undefined,
    });
    approvals.value = resp.items;
    total.value = resp.pagination.total;
  } catch (e) {
    // 错误已处理
  } finally {
    loading.value = false;
  }
}

async function handleApprove(row: Approval) {
  try {
    await ElMessageBox.confirm(`确认批准外发申请 #${row.id}？`, '审批', { type: 'success' });
    await distributionApi.decide(row.id, 'approve', '审批通过');
    ElMessage.success('已批准');
    loadData();
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已处理
    }
  }
}

async function handleReject(row: Approval) {
  try {
    const { value: comment } = await ElMessageBox.prompt('请输入驳回理由：', '驳回', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /.{5,}/,
      inputErrorMessage: '理由至少 5 个字符',
    });
    await distributionApi.decide(row.id, 'reject', comment);
    ElMessage.success('已驳回');
    loadData();
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已处理
    }
  }
}

async function handleRevoke(row: Approval) {
  try {
    await ElMessageBox.confirm(
      `紧急撤销外发 #${row.id}？\n此操作将立即吊销接收方的访问权限。`,
      '紧急撤销',
      { type: 'error' }
    );
    await distributionApi.revoke(row.id);
    ElMessage.warning('已撤销');
    loadData();
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已处理
    }
  }
}

function handleCreate() {
  ElMessage.info('新建外发审批功能待实现');
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    approved: 'success',
    rejected: 'danger',
    expired: 'info',
    revoked: 'info',
  };
  return map[status] || '';
}

function getStatusLabel(status: string) {
  const map: Record<string, string> = {
    pending: '待审批',
    approved: '已批准',
    rejected: '已驳回',
    expired: '已过期',
    revoked: '已撤销',
  };
  return map[status] || status;
}

onMounted(() => loadData());
</script>

<template>
  <div class="distribution-page">
    <div class="page-header">
      <h2>外发审批</h2>
      <el-button type="primary" @click="handleCreate">+ 发起外发</el-button>
    </div>

    <el-card>
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 140px;">
            <el-option label="待审批" value="pending" />
            <el-option label="已批准" value="approved" />
            <el-option label="已驳回" value="rejected" />
            <el-option label="已过期" value="expired" />
            <el-option label="已撤销" value="revoked" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData()">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="approvals" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="doc_id" label="文档" width="100" />
        <el-table-column label="接收方" min-width="200">
          <template #default="{ row }">
            <div>{{ row.external_recipient.name }}</div>
            <div class="text-muted">{{ row.external_recipient.email }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="理由" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status) as any" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="valid_hours" label="有效期" width="100">
          <template #default="{ row }">
            {{ row.valid_hours }}h
          </template>
        </el-table-column>
        <el-table-column label="已打开" width="100">
          <template #default="{ row }">
            {{ row.current_open_count }} / {{ row.max_open_count }}
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="创建时间" width="180" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'pending'">
              <el-button link type="success" @click="handleApprove(row)">批准</el-button>
              <el-button link type="danger" @click="handleReject(row)">驳回</el-button>
            </template>
            <template v-else-if="row.status === 'approved'">
              <el-button link type="danger" @click="handleRevoke(row)">紧急撤销</el-button>
            </template>
            <el-button link @click="ElMessage.info('查看详情：' + row.id)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        :total="total"
        :page-size="20"
        layout="total, prev, pager, next, jumper"
        @current-change="loadData"
        class="pagination"
      />
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.distribution-page { max-width: 1400px; margin: 0 auto; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  h2 { margin: 0; }
}
.pagination {
  margin-top: 20px;
  justify-content: flex-end;
  display: flex;
}
.text-muted { color: #909399; font-size: 12px; }
</style>