<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client';
import { useUserStore } from '@/stores/user';
import { describePlaybackError, formatPlaybackError } from '@/utils/hlsError';
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

/**
 * 第一次（根因）失败的文案。
 *
 * <p>为什么要单独留一份：重试会把 errorMsg 覆盖成**最后一次**的报错，
 * 而最后一次往往是**次生错误** —— 比如第一次是"密钥拉取超时"，
 * 重试后变成 hls.js 自己的状态失步
 * （{@code after key load, decryptdata unset or changed}）。
 * 用户/运维看到的是次生错误，就得不出真正的根因。
 * 这里保留首次错误，重试横幅与最终提示都以它为准。
 */
let firstErrorText = '';

/**
 * 网络类致命错误的重试次数。
 *
 * <p>放在组件作用域（而不是 createPlayer 的闭包里）是有原因的：
 * 重试会**重建** Hls 实例，闭包里的计数器会被重置 ⇒ 永远停在 1/3，
 * 变成无限重试。计数必须跨重建累计。
 */
let networkRetries = 0;
/** MEDIA_ERROR 只试一次内置恢复（recoverMediaError），与网络重试分开计数 */
let recoveryAttempted = false;
const NETWORK_MAX_RETRIES = 3;

onMounted(() => {
  loadVideo();
});

// 路由参数变化 → 重新加载（vue-router 复用组件实例时 onMounted 不会重跑）
watch(videoId, () => {
  // 先销毁旧实例，否则两个 hls 实例抢同一个 <video> → MediaSource 冲突
  stopHlsPolling();
  destroyHls();
  errorMsg.value = '';
  firstErrorText = '';
  loading.value = true;
  loadVideo();
});

async function loadVideo() {
  try {
    // 走项目 api 客户端（自动注入 JWT）
    const meta = await api.get<any>(`/videos/${videoId.value}`);
    video.value = meta;
    // 每次重新起播都清掉上一次的"根因错误"留档与重试计数
    firstErrorText = '';
    networkRetries = 0;
    recoveryAttempted = false;

    // 转码没完成时**不要去拉 m3u8** ——
    // 拉了必然 404（MinIO 里还没这个对象），Hls.js 把它当成
    // "网络异常" 报出来，用户看到的是"网络异常，正在重试（1/3）… (HTTP 404)"，
    // 完全看不出真正原因是"视频还在转码 / 转码失败了"。
    const hlsStatus = meta?.hls_status;
    if (hlsStatus === 'pending' || hlsStatus === 'processing') {
      errorMsg.value = '视频正在转码处理中，完成后本页面会自动开始播放';
      loading.value = false;
      startHlsPolling();
      return;
    }
    if (hlsStatus === 'failed') {
      errorMsg.value = '视频转码失败，无法播放。请删除后重新上传，或联系管理员处理';
      loading.value = false;
      return;
    }

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
      createPlayer(m3u8Url.value);
    } else if (videoEl.value) {
      // Safari 原生 HLS
      videoEl.value.src = m3u8Url.value;
      console.log('[VideoDetail] using native HLS (Safari path)');
    }
    loading.value = false;
  } catch (e: any) {
    console.error('[VideoDetail] load error:', e);
    // 403 = 没有该视频的查看权限（密级 L3/L4 且未被授权）。
    // axios 的通用提示是"没有权限执行该操作"，看不出是哪个视频、什么原因，
    // 这里换成能直接指导下一步的说法。
    if (e?.response?.status === 403) {
      const detail = e?.response?.data?.detail;
      errorMsg.value = detail
        ? String(detail)
        : '没有该视频的查看权限，请联系上传者或管理员授权';
    } else {
      errorMsg.value = e?.message || '加载失败';
    }
    loading.value = false;
  }
}

/**
 * 建一个 Hls 实例并挂到 <video> 上。
 *
 * <p><b>为什么重试要"重建实例"而不是 {@code hls.startLoad()}</b>
 * （2026-11-03 线上故障的第二个根因）：
 *
 * <p>hls.js 的 {@code startLoad()} 内部先调 {@code stopLoad()}，
 * 而 {@code stopLoad()} 会 {@code keyLoader.abort()} ——
 * 把**在途的密钥请求**中止掉。可中止的请求在 hls.js 的
 * {@code keyLoaderInfo} 里留下一条"promise 永不 settle"的记录
 * （{@code onAbort} 只有在 loader 真正回调时才会走到，
 * 被 stopLoad 掐掉的请求两者都不会发生）。
 *
 * <p>下一个分片再来取**同一个**密钥 URI 时，会与这条僵死记录赛跑，
 * 于是 hls.js 抛出
 * {@code after key load, decryptdata unset or changed} ——
 * <b>密钥的 HTTP 请求其实成功了</b>，是它自己的账本对不上。
 * 这个报错和真正的网络故障长得一模一样，把排查带偏。
 *
 * <p>重建实例 = 换一套全新的 {@code keyLoaderInfo}（空账本），
 * 从根上避开这个状态；顺带也把上一个实例挂着的请求全部放掉。
 */
function createPlayer(src: string) {
  // 先毁掉上一个实例 —— 否则两个实例抢同一个 <video> → MediaSource 冲突
  destroyHls();

  // <video> 还没渲染出来就别建实例（模板里它是 v-if 控制的）
  const el = videoEl.value;
  if (!el) {
    console.warn('[VideoDetail] createPlayer: <video> 还没渲染，跳过');
    return;
  }

  const instance = new Hls({
    debug: false,
    enableWorker: true,
  });
  hls.value = instance;

  // 监听全部生命周期事件，定位卡哪一步
  const log = (e: string, d?: any) => console.log('[VideoDetail] HLS', e, d || '');
  [
    'MANIFEST_LOADING', 'MANIFEST_LOADED', 'MANIFEST_PARSED',
    'LEVEL_LOADING', 'LEVEL_LOADED', 'LEVEL_SWITCHED',
    'FRAG_LOADING', 'FRAG_LOADED', 'FRAG_PARSED_INIT_SEGMENT',
    'BUFFER_APPENDING', 'BUFFER_EOS',
    'VIDEO_ATTACHING', 'VIDEO_ATTACHED',
  ].forEach((evt) => instance.on((Hls.Events as any)[evt], () => log(evt)));

  // 恢复成功 → 清掉错误横幅。
  // 否则重试成功后 errorMsg 仍非空 → 模板 v-else-if="errorMsg" 继续渲染
  // 红字，v-show="... && !errorMsg" 把播放器整个隐藏 ——
  // 视频其实已经能播了，用户却只看到一条永久的错误横幅。
  instance.on(Hls.Events.MANIFEST_PARSED, () => {
    if (errorMsg.value.startsWith('网络异常')) {
      errorMsg.value = '';
      firstErrorText = '';
    }
  });

  // 安全自动播放：只在 hls.js 完成 attach 之后再 play()，
  // 并 catch 浏览器 autoplay 拒绝（NotAllowedError），避免误判成 MEDIA_ERROR。
  // 注意：模板里不放 autoplay 属性，否则 <video> 在 MediaSource 就绪前就会触发一遍未连接 source 的 play。
  instance.on(Hls.Events.MEDIA_ATTACHED, () => {
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
  // MEDIA_ERROR 用单独的 recoveryAttempted 标志，只试一次内置恢复。
  // （两个计数器都在组件作用域，见上面的说明 —— 重建实例不能把计数清零）

  instance.on(Hls.Events.ERROR, (_e, data) => {
    // 旧实例（已经被重建替换掉）的事件一律忽略：
    // 它再去动 hls.value，会把**新**实例的播放状态搅乱
    // （旧实例的密钥请求失败时，hls.value 已经指向新实例了）。
    if (hls.value !== instance) {
      console.warn('[VideoDetail] ignoring error from stale hls instance:', data.details);
      return;
    }

    // hls.js 的 details 精确到阶段（manifest / frag / key），
    // 而 data.response 只在**真的收到 HTTP 响应**时才存在 ——
    // 超时路径上它是 undefined。原来只读 response.code，
    // 于是所有超时都退化成 "HTTP ??"，对排查零信息量。
    const errInfo = describePlaybackError(data);
    console.error('[VideoDetail] HLS error details:', JSON.stringify({
      type: data.type, details: data.details,
      fatal: data.fatal, error: data.error?.message,
      status: errInfo.status, stage: errInfo.stage,
      url: errInfo.url,
    }));
    if (!data.fatal) {
      // 非致命：hls.js 内部已自动重试，仅日志；不刷 errorMsg 防止覆盖真实致命错误
      return;
    }

    // 致命：先按类型尝试内置恢复，恢复失败 / 重试用尽再切原生 <video>
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
      // 网络类（manifest/segment 404/5xx、secure_link 拒签、AES key 拉取失败、MinIO 文件丢失等）
      if (networkRetries < NETWORK_MAX_RETRIES) {
        networkRetries++;
        // 首次（根因）错误留档；重试后的报错往往是次生错误，
        // 覆盖掉它就看不出真正出问题的环节了。
        if (!firstErrorText) {
          firstErrorText = formatPlaybackError(data);
        }
        errorMsg.value = `网络异常，正在重试（${networkRetries}/${NETWORK_MAX_RETRIES}）…` +
          `（${errInfo.stage} ${errInfo.statusText}）` +
          // 把 hls.js 的失败原因原文带上：没发出去的请求和真的网络不通
          // 在状态码上长得一模一样（都是"无响应"），只有原文能区分
          (errInfo.reason ? `：${errInfo.reason}` : '');
        console.warn(
          `[VideoDetail] fatal NETWORK_ERROR, rebuild player retry ${networkRetries}/${NETWORK_MAX_RETRIES}, ` +
          `stage=${errInfo.stage}, status=${errInfo.status ?? 'no-response'}, url=${errInfo.url}, ` +
          `reason=${errInfo.reason ?? ''}`,
        );
        // 重建实例（不是 startLoad）：见 createPlayer 的注释 —— startLoad 会
        // 中止在途的密钥请求，给 hls.js 留下一条永不 settle 的密钥记录，
        // 下一轮就会抛 'after key load, decryptdata unset or changed'。
        try { createPlayer(src); return; } catch { /* fall through */ }
      }
      // 重试耗尽 → 落 fallback 前给出真正的可操作错误（用首次错误，根因优先）
      errorMsg.value = firstErrorText || formatPlaybackError(data);
    } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
      if (!recoveryAttempted) {
        recoveryAttempted = true;
        // 解码/MediaSource/SourceBuffer 类：让 hls.js 走内置 recoverMediaError
        console.warn('[VideoDetail] fatal MEDIA_ERROR, calling recoverMediaError()');
        errorMsg.value = '视频解码异常，正在恢复…';
        try { instance.recoverMediaError(); return; } catch { /* fall through */ }
      }
      errorMsg.value = `视频解码失败 (${data.details})：已切换至兼容模式`;
    }
    // 其它（KEY_SYSTEM_ERROR / MUX_ERROR / REMUXING_ERROR / OTHER_ERROR）
    // + 已用尽重试/恢复 → 切原生 <video>

    // 必须先 destroy hls 实例，否则直接覆盖 videoEl.src 会强制把内部 MediaSource 置为 closed，
    // 触发 hls.js 的 mediaSourceRequiresReset 反复重连，造成无限级联。
    try {
      destroyHls();
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
  instance.loadSource(src);
  console.log('[VideoDetail] calling hls.attachMedia...');
  instance.attachMedia(el);
  console.log('[VideoDetail] hls.init done');
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

/**
 * 转码未完成时的轮询：等 hls_status 变成 ready 再自动开始播放。
 *
 * <p>为什么要有：视频刚上传时转码要跑一会儿（FFmpeg 切片 + AES 加密 + 指纹）。
 * 原来详情页直接去拉 m3u8 —— 必然 404，报成"网络异常 (HTTP 404)"，
 * 用户完全不知道是"还在转码"。这里改成先看状态、再决定要不要拉。
 */
const HLS_POLL_INTERVAL_MS = 3000;
const HLS_POLL_MAX_SECONDS = 600;
let hlsPollTimer: number | null = null;
let hlsPollStartedAt = 0;

function startHlsPolling() {
  stopHlsPolling();
  hlsPollStartedAt = Date.now();
  const tick = async () => {
    if (Date.now() - hlsPollStartedAt > HLS_POLL_MAX_SECONDS * 1000) {
      stopHlsPolling();
      errorMsg.value = '视频转码超时（已等待 10 分钟），请稍后刷新页面或联系管理员';
      return;
    }
    try {
      const meta = await api.get<any>(`/videos/${videoId.value}`);
      video.value = meta;
      const status = meta?.hls_status;
      if (status === 'ready') {
        stopHlsPolling();
        errorMsg.value = '';
        loading.value = true;
        await loadVideo();
        return;
      }
      if (status === 'failed') {
        stopHlsPolling();
        errorMsg.value = '视频转码失败，无法播放。请删除后重新上传，或联系管理员处理';
        return;
      }
    } catch {
      /* 轮询失败静默重试 —— 真正的错误在页面加载时已经报过了 */
    }
    hlsPollTimer = window.setTimeout(tick, HLS_POLL_INTERVAL_MS);
  };
  hlsPollTimer = window.setTimeout(tick, HLS_POLL_INTERVAL_MS);
}

function stopHlsPolling() {
  if (hlsPollTimer !== null) {
    clearTimeout(hlsPollTimer);
    hlsPollTimer = null;
  }
}

onBeforeUnmount(() => {
  // destroy + 置空，避免异步回调（如 token刷新、错误事件）触达已销毁实例
  stopHlsPolling();
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