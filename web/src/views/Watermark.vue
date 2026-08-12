<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { watermarkApi, type WatermarkExtractResult } from '@/api/watermark';

const loading = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const fileType = ref<'image' | 'pdf' | 'video' | 'docx' | 'xlsx'>('image');
const result = ref<WatermarkExtractResult | null>(null);
const fileName = ref<string>('');

function triggerFileInput() {
  fileInput.value?.click();
}

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0];
  if (!file) return;
  fileName.value = file.name;

  // 根据扩展名推断类型
  const ext = file.name.toLowerCase().split('.').pop() || '';
  if (['png', 'jpg', 'jpeg', 'bmp', 'gif'].includes(ext)) {
    fileType.value = 'image';
  } else if (ext === 'pdf') {
    fileType.value = 'pdf';
  } else if (['mp4', 'mov', 'avi', 'mkv'].includes(ext)) {
    fileType.value = 'video';
  } else if (ext === 'docx') {
    fileType.value = 'docx';
  } else if (ext === 'xlsx') {
    fileType.value = 'xlsx';
  }

  handleExtract(file);
}

async function handleExtract(file?: File) {
  const f = file || fileInput.value?.files?.[0];
  if (!f) {
    ElMessage.warning('请先选择文件');
    return;
  }

  loading.value = true;
  try {
    const formData = new FormData();
    formData.append('file', f);
    formData.append('type', fileType.value);

    const resp = await watermarkApi.extract(formData);
    result.value = resp;

    if (resp.matched_users.length > 0) {
      ElMessage.success(`发现 ${resp.matched_users.length} 个匹配用户`);
    } else {
      ElMessage.info('未找到匹配用户');
    }
  } catch (e) {
    result.value = null;
  } finally {
    loading.value = false;
  }
}

function reset() {
  result.value = null;
  fileName.value = '';
  if (fileInput.value) fileInput.value.value = '';
}

function getConfidenceColor(confidence: number) {
  if (confidence >= 0.8) return '#67c23a';
  if (confidence >= 0.5) return '#e6a23c';
  return '#f56c6c';
}
</script>

<template>
  <div class="watermark-page">
    <h2 class="page-title">水印溯源</h2>

    <el-row :gutter="20">
      <el-col :span="10">
        <el-card>
          <template #header>
            <span>上传疑似泄露文件</span>
          </template>

          <el-form label-width="80px">
            <el-form-item label="文件类型">
              <el-select v-model="fileType" style="width: 100%">
                <el-option label="图片" value="image" />
                <el-option label="PDF" value="pdf" />
                <el-option label="视频" value="video" />
                <el-option label="Word" value="docx" />
                <el-option label="Excel" value="xlsx" />
              </el-select>
            </el-form-item>
            <el-form-item label="文件">
              <el-button type="primary" @click="triggerFileInput" :loading="loading">
                选择文件
              </el-button>
              <input
                ref="fileInput"
                type="file"
                style="display: none"
                accept="image/*,.pdf,.mp4,.docx,.xlsx"
                @change="onFileChange"
              />
              <div v-if="fileName" class="file-name">
                已选择：{{ fileName }}
              </div>
            </el-form-item>
          </el-form>

          <el-alert type="info" :closable="false" show-icon>
            <template #title>支持格式</template>
            图片：PNG/JPG/BMP/GIF<br>
            文档：PDF/DOCX/XLSX<br>
            视频：MP4/MOV/AVI
          </el-alert>

          <div class="action-buttons">
            <el-button @click="reset" :disabled="!result">清空</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card v-if="result">
          <template #header>
            <span>提取结果</span>
          </template>

          <el-descriptions :column="2" border>
            <el-descriptions-item label="文件类型">
              <el-tag>{{ result.type }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="提取时间">
              {{ result.extracted.extract_time }}
            </el-descriptions-item>
            <el-descriptions-item label="提取到工号">
              <code v-if="result.extracted.employee_no">{{ result.extracted.employee_no }}</code>
              <span v-else class="text-muted">未识别</span>
            </el-descriptions-item>
            <el-descriptions-item label="置信度">
              <span :style="{ color: getConfidenceColor(result.extracted.confidence), fontWeight: 'bold' }">
                {{ (result.extracted.confidence * 100).toFixed(1) }}%
              </span>
            </el-descriptions-item>
            <el-descriptions-item v-if="result.extracted.fingerprint" label="帧指纹" :span="2">
              <code class="fingerprint">{{ result.extracted.fingerprint }}</code>
            </el-descriptions-item>
          </el-descriptions>

          <el-divider />

          <h3>匹配用户</h3>
          <el-table v-if="result.matched_users.length > 0" :data="result.matched_users" stripe>
            <el-table-column prop="user_id" label="用户 ID" width="100" />
            <el-table-column prop="employee_no" label="工号" width="150" />
            <el-table-column prop="real_name" label="姓名" width="150" />
            <el-table-column label="匹配度" width="150">
              <template #default="{ row }">
                <el-progress :percentage="row.match_score * 100" :color="getConfidenceColor(row.match_score)" />
              </template>
            </el-table-column>
            <el-table-column prop="match_time" label="访问时间" />
          </el-table>
          <el-empty v-else description="未找到匹配用户" :image-size="80" />
        </el-card>

        <el-empty v-else description="请上传文件开始水印提取" :image-size="120" />
      </el-col>
    </el-row>
  </div>
</template>

<style scoped lang="scss">
.watermark-page { max-width: 1400px; margin: 0 auto; }
.file-name {
  margin-top: 8px;
  color: #67c23a;
  font-size: 14px;
}
.action-buttons {
  margin-top: 20px;
  text-align: right;
}
.fingerprint {
  font-size: 11px;
  word-break: break-all;
}
.text-muted { color: #909399; }
</style>