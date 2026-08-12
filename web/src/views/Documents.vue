<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { documentApi, type Document } from '@/api/document';

const loading = ref(false);
const documents = ref<Document[]>([]);
const total = ref(0);
const searchForm = ref({
  classification_lv: '',
  file_type: '',
  keyword: '',
});

async function loadData(page = 1) {
  loading.value = true;
  try {
    const resp = await documentApi.list({
      page,
      page_size: 20,
      classification_lv: searchForm.value.classification_lv || undefined,
      file_type: searchForm.value.file_type || undefined,
    });
    documents.value = resp.items;
    total.value = resp.pagination.total;
  } catch (e) {
    // 错误已处理
  } finally {
    loading.value = false;
  }
}

function handleUpload() {
  ElMessage.info('文档上传功能待实现');
}

async function handlePreview(doc: Document) {
  ElMessage.info(`预览文档：${doc.title}`);
}

async function handleDelete(doc: Document) {
  try {
    await ElMessageBox.confirm(`确认删除文档「${doc.title}」？`, '提示', { type: 'warning' });
    await documentApi.delete(doc.id);
    ElMessage.success('删除成功');
    loadData();
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已处理
    }
  }
}

function handleDistribute(doc: Document) {
  ElMessage.info(`外发文档：${doc.title}`);
}

function getFileTypeLabel(type: string) {
  const map: Record<string, string> = {
    docx: 'Word',
    pdf: 'PDF',
    xlsx: 'Excel',
    pptx: 'PowerPoint',
    image: '图片',
  };
  return map[type] || type;
}

function getFileTypeColor(type: string) {
  const map: Record<string, string> = {
    docx: 'primary',
    pdf: 'danger',
    xlsx: 'success',
    pptx: 'warning',
    image: 'info',
  };
  return map[type] || '';
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    ready: 'success',
    processing: 'warning',
    pending: 'info',
    failed: 'danger',
    skipped: 'info',
  };
  return map[status] || '';
}

onMounted(() => loadData());
</script>

<template>
  <div class="documents-page">
    <div class="page-header">
      <h2>文档管理</h2>
      <el-button type="primary" @click="handleUpload">+ 上传文档</el-button>
    </div>

    <el-card>
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="密级">
          <el-select v-model="searchForm.classification_lv" placeholder="全部" clearable style="width: 120px;">
            <el-option label="L1 公开" value="L1" />
            <el-option label="L2 内部" value="L2" />
            <el-option label="L3 机密" value="L3" />
            <el-option label="L4 绝密" value="L4" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="searchForm.file_type" placeholder="全部" clearable style="width: 120px;">
            <el-option label="Word" value="docx" />
            <el-option label="PDF" value="pdf" />
            <el-option label="Excel" value="xlsx" />
            <el-option label="PowerPoint" value="pptx" />
            <el-option label="图片" value="image" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData()">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="documents" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="getFileTypeColor(row.file_type) as any" size="small">
              {{ getFileTypeLabel(row.file_type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密级" width="100">
          <template #default="{ row }">
            <el-tag :type="row.classification_lv === 'L4' ? 'danger' : row.classification_lv === 'L3' ? 'warning' : ''" size="small">
              {{ row.classification_lv }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="120">
          <template #default="{ row }">
            {{ (row.size_bytes / 1024 / 1024).toFixed(2) }} MB
          </template>
        </el-table-column>
        <el-table-column label="水印" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.watermark_status) as any" size="small">
              {{ row.watermark_status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="upload_time" label="上传时间" width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handlePreview(row)">预览</el-button>
            <el-button link type="primary" @click="handleDistribute(row)">外发</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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
.documents-page { max-width: 1400px; margin: 0 auto; }
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
</style>