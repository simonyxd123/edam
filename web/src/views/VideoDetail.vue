<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import axios from 'axios';
import Hls from 'hls.js';

const route = useRoute();
const router = useRouter();
const videoId = route.params.id as string;

const video = ref<any>(null);
const videoEl = ref<HTMLVideoElement | null>(null);
const m3u8Url = ref<string>('');
const token = ref<string>('');
const hls = ref<Hls | null>(null);
const loading = ref(true);
const errorMsg = ref('');

onMounted(async () => {
  try {
    // 1. 加载视频元数据
    const meta = await axios.get(`/api/v1/videos/${videoId}`);
    video.value = meta.data;

    // 2. 申请播放 token
    const tk = await axios.post(`/api/v1/playback/${videoId}/token`, null, {
      headers: { 'X-User-Id': '1' },
    });
    m3u8Url.value = tk.data.m3u8_url;
    token.value = tk.data.token;

    // 3. HLS.js 播放
    if (videoEl.value && Hls.isSupported()) {
      hls.value = new Hls();
      hls.value.loadSource(m3u8Url.value);
      hls.value.attachMedia(videoEl.value);
      hls.value.on(Hls.Events.ERROR, (_e, data) => {
        if (data.fatal) {
          errorMsg.value = `HLS 播放错误: ${data.type} / ${data.details}`;
        }
      });
    } else if (videoEl.value) {
      // Safari 原生 HLS
      videoEl.value.src = m3u8Url.value;
    }
    loading.value = false;
  } catch (e: any) {
    errorMsg.value = e?.message || '加载失败';
    loading.value = false;
  }
});

onBeforeUnmount(() => {
  hls.value?.destroy();
});

function goBack() {
  router.push({ name: 'Videos' });
}
</script>

<template>
  <div class="video-detail">
    <el-page-header @back="goBack" :icon="undefined">
      <template #content>
        <span class="title">{{ video?.title || `视频 #${videoId}` }}</span>
      </template>
    </el-page-header>

    <div v-if="loading" v-loading="true" class="loading">加载中…</div>
    <div v-else-if="errorMsg" class="error">{{ errorMsg }}</div>
    <div v-else>
      <video
        ref="videoEl"
        controls
        autoplay
        muted
        style="width:100%;max-height:60vh;background:#000;"
      ></video>

      <el-descriptions :column="2" border style="margin-top:24px">
        <el-descriptions-item label="视频 ID">{{ videoId }}</el-descriptions-item>
        <el-descriptions-item label="时长">{{ video?.duration_sec }}s</el-descriptions-item>
        <el-descriptions-item label="大小">{{ video?.size_bytes }} bytes</el-descriptions-item>
        <el-descriptions-item label="密级">L{{ video?.classification_lv }}</el-descriptions-item>
        <el-descriptions-item label="上传时间">{{ $fmt(video?.upload_time) }}</el-descriptions-item>
        <el-descriptions-item label="上传者">{{ video?.uploader_id }}</el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<style scoped>
.video-detail { padding: 16px; }
.title { font-size: 18px; font-weight: 600; }
.loading { height: 400px; }
.error { color: #f56c6c; padding: 16px; }
</style>