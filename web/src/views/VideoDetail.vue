<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, apiBase } from '@/api/client';
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
    // 走项目 api 客户端（自动注入 JWT + X-User-Id）
    const meta = await api.get<any>(`/videos/${videoId}`);
    video.value = meta;

    const tk = await api.post<{ m3u8_url: string; token: string; session_id: string; key_url: string }>(
      `/playback/${videoId}/token`,
    );
    // 后端返回的 m3u8_url 是相对路径 '/api/v1/playback/{id}/playlist.m3u8?token=...'
    const fullUrl = tk.m3u8_url.startsWith('http')
      ? tk.m3u8_url
      : `${window.location.origin}${tk.m3u8_url}`;
    m3u8Url.value = fullUrl;
    token.value = tk.token;
    console.log('[VideoDetail] m3u8 URL:', fullUrl);

    // 3. HLS.js 播放
    if (videoEl.value && Hls.isSupported()) {
      hls.value = new Hls({ debug: false, enableWorker: true });
      hls.value.on(Hls.Events.ERROR, (_e, data) => {
        console.error('[VideoDetail] HLS error:', data);
        if (data.fatal) {
          const msg = `HLS ${data.type}/${data.details}: ${data.error?.message || ''}`;
          errorMsg.value = msg;
          // 网络错误 → 切原始 <video src> 模式重试（部分浏览器会处理）
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            console.warn('[VideoDetail] NETWORK_ERROR, retry with native <video>');
          }
        }
      });
      hls.value.on(Hls.Events.MANIFEST_PARSED, () => {
        console.log('[VideoDetail] manifest parsed, attaching to video');
      });
      hls.value.loadSource(m3u8Url.value);
      hls.value.attachMedia(videoEl.value);
    } else if (videoEl.value) {
      // Safari 原生 HLS
      videoEl.value.src = m3u8Url.value;
    }
    loading.value = false;
  } catch (e: any) {
    console.error('[VideoDetail] load error:', e);
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