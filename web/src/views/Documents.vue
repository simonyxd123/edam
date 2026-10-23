<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { documentApi, type Document } from '@/api/document';
import { UploadFilled, Document as DocIcon } from '@element-plus/icons-vue';
import { isDialogDismissed } from '@/utils/dialog';

const loading = ref(false);
const documents = ref<Document[]>([]);
const total = ref(0);
const searchForm = ref({
  classification_lv: '',
  file_type: '',
  keyword: '',
});

// 上传对话框状态
const uploadDialogVisible = ref(false);
const uploadForm = ref({
  file: null as File | null,
  title: '',
  classification_lv: 'L2',
  enable_watermark: true,
});
const uploadRules = {
  file: [{ required: true, message: '请选择文档', trigger: 'change' }],
  classification_lv: [{ required: true, message: '请选择密级', trigger: 'change' }],
};
const uploadFormRef = ref();
const uploading = ref(false);
const uploadProgress = ref(0);

// 预览对话框状态
const previewDialogVisible = ref(false);
const previewDoc = ref<Document | null>(null);
const previewSrc = ref<string>('');
const previewMime = ref<string>('');
const previewLoading = ref(false);   // PDF 流可能几百 MB，需要 loading 防双击
let previewBlobUrl = '';             // 非响应：记下当前 blob URL，关弹窗 / 重开时 revoke
/**
 * 预览加载序号（stale-guard）。
 *
 * <p>快速切换预览时会并发多个 fetch：先发的请求可能后到。
 * 原来的实现里，后到的旧请求会**覆盖**新请求的 blob URL，
 * 而它自己 revoke 掉的又可能是新 URL —— 最终 iframe 指向一个
 * 已被 revoke 的地址 → 白屏。
 *
 * <p>用递增序号判定"我还是最新那个吗"，不是最新就直接丢弃自己的结果。
 */
let previewLoadSeq = 0;
/** 兜底回收定时器句柄（关弹窗 / 组件卸载时要清掉） */
let previewRevokeTimer: number | undefined;

function canPreviewInline(mime: string | undefined): boolean {
  if (!mime) return false;
  return mime.startsWith('application/pdf') || mime.startsWith('image/') || mime.startsWith('text/');
}

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

function openUploadDialog() {
  uploadForm.value = { file: null, title: '', classification_lv: 'L2', enable_watermark: true };
  uploadProgress.value = 0;
  uploadDialogVisible.value = true;
}

function onFileChange(file: any) {
  uploadForm.value.file = file?.raw ?? null;
  // 默认标题 = 文件名（去后缀）
  if (!uploadForm.value.title && file?.name) {
    uploadForm.value.title = file.name.replace(/\.[^.]+$/, '');
  }
}

async function submitUpload() {
  if (!uploadForm.value.file) {
    ElMessage.warning('请选择文档');
    return;
  }
  const MAX = 50 * 1024 * 1024;
  if (uploadForm.value.file.size > MAX) {
    ElMessage.error('文档不能超过 50 MB');
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
    fd.append('enable_watermark', String(uploadForm.value.enable_watermark));

    // 用项目 api 客户端（自动注入 JWT + X-User-Id + 真实进度）
    const result = await api.upload<{ doc_id: number; file_hash: string }>(
      '/documents', fd,
      (loaded, total) => {
        uploadProgress.value = Math.round((loaded / total) * 100);
      }
    );

    ElMessage.success(
      `上传成功，doc_id=${result.doc_id}，正在后台处理水印 + 预览…`
    );
    uploadDialogVisible.value = false;
    loadData();
  } catch (e: any) {
    // 拦截器已经弹过提示的（超时 / 4xx / 5xx）不再重复弹
    if (!e?.__handled) ElMessage.error(e?.message || '上传失败');
  } finally {
    uploading.value = false;
  }
}

async function handlePreview(doc: Document) {
  previewDoc.value = doc;
  previewMime.value = doc.mime_type || '';
  previewDialogVisible.value = true;
  previewLoading.value = true;

  // 走 Blob URL 路线：先用 axios 带 JWT 拉字节流，转 Blob 喂给 iframe
  // —— iframe 不会自动带 Authorization 头；如果直接把 src 设为 `/api/v1/.../preview`，
  // 后端 SecurityConfig 会 401，浏览器表现成 ERR_CONNECTION_REFUSED 或反复重试，
  // 在 dev/proxy 链复杂的测试环境下经常被误导成 "X.X.X.X refused to connect"。
  // Blob URL 是浏览器本地资源，不发任何 HTTP，天然绕过鉴权与同源问题。
  const mySeq = ++previewLoadSeq;
  try {
    // 先把上一个 blob URL 释放，避免连续打开多个预览时内存泄漏
    releasePreviewBlob();
    const token = localStorage.getItem('access_token');
    const userId = localStorage.getItem('user_id');
    const r = await fetch(`/api/v1/documents/${doc.id}/preview`, {
      headers: {
        Authorization: 'Bearer ' + (token || ''),
        'X-User-Id': userId || '',
      },
    });
    if (!r.ok) {
      throw new Error(`HTTP ${r.status}`);
    }
    const blob = await r.blob();

    // 已经有更新的预览请求了 → 丢弃本次结果（否则会覆盖新内容）
    if (mySeq !== previewLoadSeq) {
      return;
    }

    // 给 Blob 套上对应的 MIME，否则部分浏览器会用通用 octet-stream 处理
    const typed = doc.mime_type ? blob.slice(0, blob.size, doc.mime_type) : blob;
    previewBlobUrl = URL.createObjectURL(typed);
    previewSrc.value = previewBlobUrl;
    // 兜底回收：1 小时后回收（即使弹窗异常没走到 closePreview）
    schedulePreviewRevoke();
  } catch (e: any) {
    if (mySeq !== previewLoadSeq) {
      return;   // 已被更新的请求取代，别弹错误提示
    }
    console.error('[Documents] preview_load_failed:', e);
    ElMessage.error('预览加载失败：' + (e?.message || '未知错误'));
    previewSrc.value = '';
  } finally {
    if (mySeq === previewLoadSeq) {
      previewLoading.value = false;
    }
  }
}

/** 释放当前 blob URL 并清掉兜底定时器 */
function releasePreviewBlob() {
  if (previewRevokeTimer !== undefined) {
    clearTimeout(previewRevokeTimer);
    previewRevokeTimer = undefined;
  }
  if (previewBlobUrl) {
    URL.revokeObjectURL(previewBlobUrl);
    previewBlobUrl = '';
  }
}

/** 安排 1 小时后的兜底回收 */
function schedulePreviewRevoke() {
  if (previewRevokeTimer !== undefined) {
    clearTimeout(previewRevokeTimer);
  }
  previewRevokeTimer = window.setTimeout(() => {
    previewRevokeTimer = undefined;
    if (previewBlobUrl) {
      URL.revokeObjectURL(previewBlobUrl);
      previewBlobUrl = '';
    }
  }, 60 * 60 * 1000);
}

function closePreview() {
  previewDialogVisible.value = false;
  // 主动回收 blob URL + 兜底定时器，否则浏览器要等 ~60min 才释放
  releasePreviewBlob();
  previewSrc.value = '';
  previewDoc.value = null;
}

function downloadPreview() {
  if (!previewDoc.value) return;
  // 用 a 标签强制下载
  const a = document.createElement('a');
  a.href = previewSrc.value;
  a.download = previewDoc.value.title || 'document';
  a.target = '_blank';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function openInNewTab() {
  // previewSrc 现在是 Blob URL（由 handlePreview 已 fetch + blob 生成），
  // 直接 window.open 即可，不必再 fetch 一次
  if (!previewBlobUrl) {
    ElMessage.warning('请等待预览加载完成');
    return;
  }
  // 复制一份 blob URL 给新窗口，避免当前弹窗关掉时 revoke 影响对方
  const fresh = previewBlobUrl;
  window.open(fresh, '_blank', 'noopener,noreferrer');
}

async function handleDelete(doc: Document) {
  try {
    await ElMessageBox.confirm(`确认删除文档「${doc.title}」？`, '提示', { type: 'warning' });
    await documentApi.delete(doc.id);
    ElMessage.success('删除成功');
    loadData();
  } catch (e) {
    if (!isDialogDismissed(e)) {
      // 错误已处理
    }
  }
}

function handleDistribute(doc: Document) {
  ElMessage.info(`外发文档：${doc.title}（TODO: 接外发审批流程）`);
}

function getFileTypeLabel(type: string) {
  const map: Record<string, { label: string; type: string }> = {
    docx: { label: 'Word', type: 'primary' },
    pdf: { label: 'PDF', type: 'danger' },
    xlsx: { label: 'Excel', type: 'success' },
    pptx: { label: 'PowerPoint', type: 'warning' },
    image: { label: '图片', type: 'info' },
  };
  return map[type] || { label: type, type: '' };
}

// el-tag 的 type 只接受 'primary' | 'success' | 'warning' | 'info' | 'danger' | undefined
type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger';

function getStatusType(status: string): TagType | undefined {
  const map: Record<string, TagType> = {
    ready: 'success',
    processing: 'warning',
    pending: 'info',
    failed: 'danger',
    skipped: 'info',
  };
  return map[status];
}

/** 密级标签颜色：L4 绝密=红，L3 机密=橙，其余不染色 */
function classificationTagType(lv: string | undefined): TagType | undefined {
  if (lv === 'L4') return 'danger';
  if (lv === 'L3') return 'warning';
  return undefined;
}

onMounted(() => loadData());

// 组件卸载时必须清理：否则 blob URL 与 1 小时的兜底定时器会一直挂着
// （切路由后浏览器仍持有预览内容，且定时器回调会打到已卸载的组件）
onBeforeUnmount(() => {
  previewLoadSeq++;      // 让在途的 fetch 结果作废
  releasePreviewBlob();
});
</script>

<template>
  <div class="documents-page">
    <div class="page-header">
      <h2>文档管理</h2>
      <el-button type="primary" @click="openUploadDialog">+ 上传文档</el-button>
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
            <el-tag :type="getFileTypeLabel(row.file_type).type as any" size="small">
              {{ getFileTypeLabel(row.file_type).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="密级" width="100">
          <template #default="{ row }">
            <el-tag :type="classificationTagType(row.classification_lv)" size="small">
              {{ row.classification_lv }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="120">
          <template #default="{ row }">
            <template v-if="row.size_bytes != null">
              {{ (row.size_bytes / 1024 / 1024).toFixed(2) }} MB
            </template>
            <template v-else>—</template>
          </template>
        </el-table-column>
        <el-table-column label="水印" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.watermark_status) as any" size="small">
              {{ row.watermark_status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">
            {{ $fmt(row.upload_time) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handlePreview(row as Document)">预览</el-button>
            <el-button link type="primary" @click="handleDistribute(row as Document)">外发</el-button>
            <el-button link type="danger" @click="handleDelete(row as Document)">删除</el-button>
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

    <!-- 文档预览对话框 -->
    <el-dialog
      v-model="previewDialogVisible"
      :title="`预览 — ${previewDoc?.title ?? ''}`"
      width="80%"
      :close-on-click-modal="false"
      top="5vh"
      @close="closePreview"
    >
      <div v-if="previewDoc" class="preview-container">
        <div v-if="previewLoading" v-loading="true" class="preview-loading">加载预览…</div>
        <iframe
          v-else-if="canPreviewInline(previewMime) && previewSrc"
          :src="previewSrc"
          class="preview-frame"
          :title="previewDoc.title"
        ></iframe>
        <div v-else-if="canPreviewInline(previewMime)" class="preview-loading">
          预览未加载：{{ previewSrc ? '' : '链接为空' }}
        </div>
        <div v-else class="preview-fallback">
          <el-icon :size="64"><DocIcon /></el-icon>
          <h3>{{ previewDoc.title }}</h3>
          <p class="mime">文件类型：{{ previewMime || '未知' }}</p>
          <p class="hint">浏览器无法直接预览此格式</p>
          <div class="actions">
            <el-button type="primary" @click="downloadPreview">下载文件</el-button>
            <el-button @click="openInNewTab">在新窗口打开</el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="closePreview">关闭</el-button>
        <el-button
          v-if="previewDoc && !canPreviewInline(previewMime)"
          type="primary"
          @click="downloadPreview"
        >下载</el-button>
      </template>
    </el-dialog>

    <!-- 上传文档对话框 -->
    <el-dialog
      v-model="uploadDialogVisible"
      title="上传文档"
      width="540px"
      :close-on-click-modal="false"
      :close-on-press-escape="!uploading"
    >
      <el-form
        ref="uploadFormRef"
        :model="uploadForm"
        :rules="uploadRules"
        label-width="100px"
      >
        <el-form-item label="文档" prop="file">
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-exceed="() => ElMessage.warning('只能选 1 个文件')"
            accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg,.gif,.bmp"
            drag
          >
            <div v-if="!uploadForm.file" class="upload-placeholder">
              <el-icon :size="48"><UploadFilled /></el-icon>
              <div>点击或拖拽文档到此处</div>
              <div class="upload-hint">PDF / Word / Excel / PPT / 图片，最大 50 MB</div>
            </div>
            <div v-else class="upload-selected">
              <el-icon :size="32"><DocIcon /></el-icon>
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

        <el-form-item label="动态水印">
          <el-switch v-model="uploadForm.enable_watermark" />
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
.preview-container {
  width: 100%;
  height: 70vh;
  min-height: 500px;
}
.preview-loading {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 14px;
}
.preview-frame {
  width: 100%;
  height: 100%;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.preview-fallback {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;
  color: #606266;
  h3 { margin: 0; font-size: 18px; }
  .mime { font-size: 13px; color: #909399; margin: 0; }
  .hint { font-size: 14px; color: #909399; margin: 0; }
  .actions { display: flex; gap: 12px; margin-top: 12px; }
}
</style>