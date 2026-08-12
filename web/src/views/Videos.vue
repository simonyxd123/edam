<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRouter } from 'vue-router';
import { videoApi, type Video } from '@/api/video';

const router = useRouter();
const loading = ref(false);
const videos = ref<Video[]>([]);
const total = ref(0);
const searchForm = ref({
  classification_lv: '',
  keyword: '',
});

async function loadData(page = 1) {
  loading.value = true;
  try {
    const resp = await videoApi.list({
      page,
      page_size: 20,
      classification_lv: searchForm.value.classification_lv || undefined,
    });
    videos.value = resp.items;
    total.value = resp.pagination.total;
  } catch (e) {
    // 错误已处理
  } finally {
    loading.value = false;
  }
}

function handleUpload() {
  ElMessage.info('上传功能待实现');
}

function handleView(video: Video) {
  router.push(`/videos/${video.id}`);
}

async function handleDelete(video: Video) {
  try {
    await ElMessageBox.confirm(`确认删除视频「${video.title}」？`, '提示', { type: 'warning' });
    await videoApi.delete(video.id);
    ElMessage.success('删除成功');
    loadData();
  } catch (e) {
    if (e !== 'cancel') {
      // 错误已处理
    }
  }
}

function getClassificationTag(lv: string) {
  const map: Record<string, { label: string; type: string }> = {
    L1: { label: 'L1 公开', type: 'info' },
    L2: { label: 'L2 内部', type: '' },
    L3: { label: 'L3 机密', type: 'warning' },
    L4: { label: 'L4 绝密', type: 'danger' },
  };
  return map[lv] || { label: lv, type: '' };
}

onMounted(() => loadData());
</script>

<template>
  <div class="videos-page">
    <div class="page-header">
      <h2>视频管理</h2>
      <el-button type="primary" @click="handleUpload">+ 上传视频</el-button>
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
        <el-form-item>
          <el-button type="primary" @click="loadData()">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="videos" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="密级" width="100">
          <template #default="{ row }">
            <el-tag :type="getClassificationTag(row.classification_lv).type as any">
              {{ getClassificationTag(row.classification_lv).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="100">
          <template #default="{ row }">
            {{ Math.floor(row.duration_sec / 60) }}:{{ String(row.duration_sec % 60).padStart(2, '0') }}
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">
            {{ (row.size_bytes / 1024 / 1024).toFixed(2) }} MB
          </template>
        </el-table-column>
        <el-table-column prop="upload_time" label="上传时间" width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.hls_status === 'ready' ? 'success' : 'warning'" size="small">
              {{ row.hls_status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleView(row)">播放</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="searchForm"
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
.videos-page { max-width: 1400px; margin: 0 auto; }
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