<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRouter } from 'vue-router';
import { videoApi, type Video } from '@/api/video';
import { useUserStore } from '@/stores/user';
import { UploadFilled, VideoCamera } from '@element-plus/icons-vue';

const router = useRouter();
const userStore = useUserStore();
const loading = ref(false);
const videos = ref<Video[]>([]);
const total = ref(0);
const searchForm = ref({
  classification_lv: '',
  keyword: '',
});

// 上传对话框状态
const uploadDialogVisible = ref(false);
const uploadForm = ref({
  file: null as File | null,
  title: '',
  classification_lv: 'L2',
});
const uploadRules = {
  file: [{ required: true, message: '请选择视频文件', trigger: 'change' }],
  classification_lv: [{ required: true, message: '请选择密级', trigger: 'change' }],
};
const uploadFormRef = ref();
const uploading = ref(false);
const uploadProgress = ref(0);

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

function openUploadDialog() {
  uploadForm.value = { file: null, title: '', classification_lv: 'L2' };
  uploadProgress.value = 0;
  uploadDialogVisible.value = true;
}

function onFileChange(file: any) {
  // el-upload 的 before-upload 钩子里已经设置了 file，这里同步一下
  uploadForm.value.file = file?.raw ?? null;
  // 默认标题 = 文件名（去后缀）
  if (!uploadForm.value.title && file?.name) {
    uploadForm.value.title = file.name.replace(/\.[^.]+$/, '');
  }
}

async function submitUpload() {
  if (!uploadForm.value.file) {
    ElMessage.warning('请选择视频文件');
    return;
  }
  // 简单校验：文件大小上限 2GB
  const MAX = 2 * 1024 * 1024 * 1024;
  if (uploadForm.value.file.size > MAX) {
    ElMessage.error('视频文件不能超过 2 GB');
    return;
  }

  uploading.value = true;
  uploadProgress.value = 0;
  try {
    const fd = new FormData();
    fd.append('file', uploadForm.value.file);
    fd.append('classification_lv', uploadForm.value.classification_lv);
    if (uploadForm.value.title) {
      fd.append('title', uploadForm.value.title);
    }

    // 用 XMLHttpRequest 实现真进度（axios  fetch 也可，但 XHR 进度事件更稳）
    const result = await uploadWithProgress('/api/v1/videos', fd, (e) => {
      if (e.lengthComputable) {
        uploadProgress.value = Math.round((e.loaded / e.total) * 100);
      }
    });

    ElMessage.success(
      `上传成功，video_id=${result.video_id}，正在后台转 HLS…`
    );
    uploadDialogVisible.value = false;
    loadData();
  } catch (e: any) {
    ElMessage.error(e?.message || '上传失败');
  } finally {
    uploading.value = false;
  }
}

/**
 * 带真实上传进度的 FormData POST
 * （axios 默认 fetch 不暴露进度，回退到 XHR）
 */
function uploadWithProgress(
  url: string,
  formData: FormData,
  onProgress: (e: ProgressEvent) => void,
): Promise<any> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);
    xhr.upload.addEventListener('progress', onProgress);
    xhr.upload.addEventListener('error', () => reject(new Error('网络错误')));
    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)); }
        catch { resolve(xhr.responseText); }
      } else {
        // 尝试解析后端错误消息
        let msg = `HTTP ${xhr.status}`;
        try { msg = JSON.parse(xhr.responseText)?.message || msg; } catch {}
        reject(new Error(msg));
      }
    });
    xhr.addEventListener('error', () => reject(new Error('网络错误')));
    // 用户 ID 由后端 controller 从 X-User-Id header 读；测试环境硬编码 1
    xhr.setRequestHeader('X-User-Id', String(userStore.user?.id ?? 1));
    xhr.send(formData);
  });
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
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">
            {{ $fmt(row.upload_time) }}
          </template>
        </el-table-column>
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

    <!-- 上传视频对话框 -->
    <el-dialog
      v-model="uploadDialogVisible"
      title="上传视频"
      width="540px"
      :close-on-click-modal="false"
      :close-on-press-escape="!uploading"
    >
      <el-form
        ref="uploadFormRef"
        :model="uploadForm"
        :rules="uploadRules"
        label-width="84px"
      >
        <el-form-item label="视频文件" prop="file">
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-exceed="() => ElMessage.warning('只能选 1 个文件')"
            accept="video/*"
            drag
          >
            <div v-if="!uploadForm.file" class="upload-placeholder">
              <el-icon :size="48"><UploadFilled /></el-icon>
              <div>点击或拖拽视频到此处</div>
              <div class="upload-hint">支持 MP4 / MOV / MKV，最大 2 GB</div>
            </div>
            <div v-else class="upload-selected">
              <el-icon :size="32"><VideoCamera /></el-icon>
              <span>{{ uploadForm.file.name }}</span>
              <span class="upload-size">
                ({{ (uploadForm.file.size / 1024 / 1024).toFixed(2) }} MB)
              </span>
            </div>
          </el-upload>
        </el-form-item>

        <el-form-item label="标题" prop="title">
          <el-input
            v-model="uploadForm.title"
            placeholder="留空则用文件名"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="密级" prop="classification_lv">
          <el-select v-model="uploadForm.classification_lv" style="width:100%">
            <el-option label="L1 公开" value="L1" />
            <el-option label="L2 内部" value="L2" />
            <el-option label="L3 机密" value="L3" />
            <el-option label="L4 绝密" value="L4" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="uploading" label="上传中">
          <el-progress :percentage="uploadProgress" :stroke-width="14" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="uploadDialogVisible = false" :disabled="uploading">取消</el-button>
        <el-button
          type="primary"
          :loading="uploading"
          :disabled="!uploadForm.file"
          @click="submitUpload"
        >
          {{ uploading ? `上传中 ${uploadProgress}%` : '开始上传' }}
        </el-button>
      </template>
    </el-dialog>
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
.upload-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 0;
  color: #909399;
  .upload-hint { font-size: 12px; color: #c0c4cc; }
}
.upload-selected {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  color: #303133;
  .upload-size { color: #909399; font-size: 12px; }
}
</style>