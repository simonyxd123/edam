<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client';
import { useUserStore } from '@/stores/user';
import Hls from 'hls.js';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
// 响应式：原来是一次性快照 const videoId = route.params.id，
// 从 /videos/1 跳 /videos/2 时 vue-router 复用同一组件实例，
// onMounted 不再执行 → 播的还是上一个视频
const videoId = computed(() => route.params.id as string);

// 当前用户的工号（用于视频水印，按预览者动态显示）
const currentEmployeeNo = computed(() => userStore.user?.employee_no || 'anonymous');

const video = ref<any>(null);
const videoEl = ref<HTMLVideoElement | null>(null);
const m3u8Url = ref<string>('');
const token = ref<string>('');
const hls = ref<Hls | null>(null);
const loading = ref(true);
const errorMsg = ref('');

onMounted(() => {
  loadVideo();
});

// 路由参数变化 → 重新加载（vue-router 复用组件实例时 onMounted 不会重跑）
watch(videoId, () => {
  // 先销毁旧实例，否则两个 hls 实例抢同一个 <video> → MediaSource 冲突
  destroyHls();
  errorMsg.value = '';
  loading.value = true;
  loadVideo();
});

async function loadVideo() {
  try {
    // 走项目 api 客户端（自动注入 JWT）
    const meta = await api.get<any>(`/videos/${videoId.value}`);
    video.value = meta;

    const tk = await api.post<{ m3u8_url: string; token: string; session_id: string; key_url: string }>(
      `/playback/${videoId.value}/token`,
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

      // 恢复成功 → 清掉错误横幅。
      // 否则重试成功后 errorMsg 仍非空 → 模板 v-else-if="errorMsg" 继续渲染
      // 红字，v-show="... && !errorMsg" 把播放器整个隐藏 ——
      // 视频其实已经能播了，用户却只看到一条永久的错误横幅。
      hls.value.on(Hls.Events.MANIFEST_PARSED, () => {
        if (errorMsg.value.startsWith('网络异常')) {
          errorMsg.value = '';
        }
      });

      // 安全自动播放：只在 hls.js 完成 attach 之后再 play()，
      // 并 catch 浏览器 autoplay 拒绝（NotAllowedError），避免误判成 MEDIA_ERROR。
      // 注意：模板里不放 autoplay 属性，否则 <video> 在 MediaSource 就绪前就会触发一遍未连接 source 的 play。
      hls.value.on(Hls.Events.MEDIA_ATTACHED, () => {
        if (!videoEl.value) return;
        videoEl.value.muted = true;
        const p = videoEl.value.play();
        if (p && typeof p.catch === 'function') {
          p.catch((err: any) =>
            console.warn('[VideoDetail] autoplay blocked, waiting user gesture:', err?.name || err),
          );
        }
      });

      // NETWORK_ERROR 重试有上限，避免后端持续 4xx/5xx 时无限刷 spinner；
      // MEDIA_ERROR 用单独的 recoverAttempted 标志，只试一次内置恢复。
      const NETWORK_MAX_RETRIES = 3;
      let networkRetries = 0;
      let recoveryAttempted = false;

      hls.value.on(Hls.Events.ERROR, (_e, data) => {
        console.error('[VideoDetail] HLS error details:', JSON.stringify({
          type: data.type, details: data.details,
          fatal: data.fatal, error: data.error?.message,
          response: data.response, networkDetails: data.networkDetails,
        }));
        if (!data.fatal) {
          // 非致命：hls.js 内部已自动重试，仅日志；不刷 errorMsg 防止覆盖真实致命错误
          return;
        }

        // 致命：先按类型尝试内置恢复，恢复失败 / 重试用尽再切原生 <video>
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          // 网络类（manifest/segment 404/5xx、secure_link 拒签、AES key 拉取失败、MinIO 文件丢失等）
          const status = data.response?.code;
          const body = (data.response?.text || '').slice(0, 80);
          if (networkRetries < NETWORK_MAX_RETRIES) {
            networkRetries++;
            errorMsg.value = `网络异常，正在重试（${networkRetries}/${NETWORK_MAX_RETRIES}）…` +
              (status ? ` (HTTP ${status})` : '');
            console.warn(
              `[VideoDetail] fatal NETWORK_ERROR, startLoad retry ${networkRetries}/${NETWORK_MAX_RETRIES}, ` +
              `status=${status ?? '??'}, body=${JSON.stringify(body)}`,
            );
            try { hls.value?.startLoad(); return; } catch { /* fall through */ }
          }
          // 重试耗尽 → 落 fallback 前给出真正的可操作错误
          errorMsg.value =
            `视频源不可达 (HTTP ${status ?? '??'})：` +
            (status === 404
              ? '视频文件在对象存储中不存在，请联系管理员重新处理'
              : '请检查网络或稍后重试');
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          if (!recoveryAttempted) {
            recoveryAttempted = true;
            // 解码/MediaSource/SourceBuffer 类：让 hls.js 走内置 recoverMediaError
            console.warn('[VideoDetail] fatal MEDIA_ERROR, calling recoverMediaError()');
            errorMsg.value = '视频解码异常，正在恢复…';
            try { hls.value?.recoverMediaError(); return; } catch { /* fall through */ }
          }
          errorMsg.value = `视频解码失败 (${data.details})：已切换至兼容模式`;
        }
        // 其它（KEY_SYSTEM_ERROR / MUX_ERROR / REMUXING_ERROR / OTHER_ERROR）
        // + 已用尽重试/恢复 → 切原生 <video>

        // 必须先 destroy hls 实例，否则直接覆盖 videoEl.src 会强制把内部 MediaSource 置为 closed，
        // 触发 hls.js 的 mediaSourceRequiresReset 反复重连，造成无限级联。
        try {
          hls.value?.destroy();
          hls.value = null;
          if (videoEl.value) {
            videoEl.value.removeAttribute('src');
            videoEl.value.load();
            videoEl.value.src = m3u8Url.value;
          }
          console.warn('[VideoDetail] fell back to native <video src>');
        } catch (e) {
          console.warn('[VideoDetail] native fallback failed:', e);
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
}

/** 销毁当前 hls 实例（切视频 / 卸载前都要调） */
function destroyHls() {
  try {
    hls.value?.destroy();
  } catch {
    /* noop */
  }
  hls.value = null;
}

onBeforeUnmount(() => {
  // destroy + 置空，避免异步回调（如 token刷新、错误事件）触达已销毁实例
  destroyHls();
  if (videoEl.value) {
    try {
      videoEl.value.pause();
      videoEl.value.removeAttribute('src');
      videoEl.value.load();
    } catch (e) {
      /* noop */
    }
  }
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