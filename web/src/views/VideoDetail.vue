<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, apiBase } from '@/api/client';
import { useUserStore } from '@/stores/user';
import Hls from 'hls.js';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const videoId = route.params.id as string;

// 当前用户的工号（用于视频水印，按预览者动态显示）
const currentEmployeeNo = computed(() => userStore.user?.employee_no || 'anonymous');

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
    console.log('[VideoDetail] checking videoEl.value=', !!videoEl.value, 'hls supported=', Hls.isSupported());
    // 等 DOM 更新（video 标签在 v-if 里，loading.value = false 后才渲染）
    await nextTick();
    console.log('[VideoDetail] after nextTick videoEl.value=', !!videoEl.value);
    if (videoEl.value && Hls.isSupported()) {
      hls.value = new Hls({
        debug: false,
        enableWorker: true,
        xhrSetup: (xhr) => {},
      });
      // 监听全部生命周期事件，定位卡哪一步
      const log = (e: string, d?: any) => console.log('[VideoDetail] HLS', e, d || '');
      [
        'MANIFEST_LOADING', 'MANIFEST_LOADED', 'MANIFEST_PARSED',
        'LEVEL_LOADING', 'LEVEL_LOADED', 'LEVEL_SWITCHED',
        'FRAG_LOADING', 'FRAG_LOADED', 'FRAG_PARSED_INIT_SEGMENT',
        'BUFFER_APPENDING', 'BUFFER_EOS',
        'VIDEO_ATTACHING', 'VIDEO_ATTACHED',
      ].forEach((evt) => hls.value?.on((Hls.Events as any)[evt], () => log(evt)));
      hls.value.on(Hls.Events.ERROR, (_e, data) => {
        console.error('[VideoDetail] HLS error details:', JSON.stringify({
          type: data.type, details: data.details,
          fatal: data.fatal, error: data.error?.message,
          response: data.response, networkDetails: data.networkDetails,
        }));
        if (data.fatal) {
          errorMsg.value = `HLS ${data.type}/${data.details}: ${data.error?.message || ''}`;
          // 致命错误 → 切原生 <video> 模式重试
          try {
            if (videoEl.value) videoEl.value.src = m3u8Url.value;
            console.warn('[VideoDetail] fell back to native <video src>');
          } catch (e) {}
        }
      });
      console.log('[VideoDetail] calling hls.loadSource...');
      hls.value.loadSource(m3u8Url.value);
      console.log('[VideoDetail] calling hls.attachMedia...');
      hls.value.attachMedia(videoEl.value);
      console.log('[VideoDetail] hls.init done');
    } else if (videoEl.value) {
      // Safari 原生 HLS
      videoEl.value.src = m3u8Url.value;
      console.log('[VideoDetail] using native HLS (Safari path)');
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

    <!-- 始终渲染 video 标签（用 v-show 控制可见），
         让 ref=\"videoEl\" 在 onMounted 里立即可用 -->
    <div v-if="loading" v-loading="true" class="loading">加载中…</div>
    <div v-else-if="errorMsg" class="error">{{ errorMsg }}</div>

    <!-- 视频容器：相对定位以便动态水印 absolute 浮在上面 -->
    <div
      v-show="!loading && !errorMsg && !!m3u8Url"
      style="position:relative;width:100%;max-height:60vh;background:#000;"
    >
      <video
        ref="videoEl"
        controls
        autoplay
        muted
        style="width:100%;max-height:60vh;background:#000;display:block;"
      ></video>
      <!-- 客户端动态水印：显示当前预览者的 employee_no（按用户切换） -->
      <div class="video-watermark">
        EDAM {{ currentEmployeeNo }} · 视频 {{ videoId }} · {{ new Date().toLocaleString('zh-CN') }}
      </div>
    </div>

    <el-descriptions
      v-show="!loading && !errorMsg"
      :column="2"
      border
      style="margin-top:24px"
    >
      <el-descriptions-item label="视频 ID">{{ videoId }}</el-descriptions-item>
      <el-descriptions-item label="时长">{{ video?.duration_sec }}s</el-descriptions-item>
      <el-descriptions-item label="大小">{{ video?.size_bytes }} bytes</el-descriptions-item>
      <el-descriptions-item label="密级">L{{ video?.classification_lv }}</el-descriptions-item>
      <el-descriptions-item label="上传时间">{{ $fmt(video?.upload_time) }}</el-descriptions-item>
      <el-descriptions-item label="上传者">{{ video?.uploader_id }}</el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<style scoped>
.video-detail { padding: 16px; }
.title { font-size: 18px; font-weight: 600; }
.loading { height: 400px; }

/* 视频水印：固定在右下角，半透明白色小字，不挡视频控件点击 */
.video-watermark {
  position: absolute;
  right: 20px;
  bottom: 50px;  /* 略高于 controls bar */
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
  font-family: monospace;
  padding: 6px 12px;
  background: rgba(0, 0, 0, 0.45);
  border-radius: 4px;
  pointer-events: none;  /* 不挡视频控件点击 */
  z-index: 999;
  user-select: none;
  white-space: nowrap;
}
.error { color: #f56c6c; padding: 16px; }
</style>