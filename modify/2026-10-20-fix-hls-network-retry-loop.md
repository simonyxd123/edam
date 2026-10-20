# 修复视频播放"网络异常，正在重试…"无上限 spinner

- 日期：2026-10-20
- 影响：web/src/views/VideoDetail.vue
- 现象：进视频详情页播放，UI 一直显示 `网络异常，正在重试…`，
  永远不会停下，控制台不停刷 hls.js ERROR 事件

## 根因（两层）

### 根因 1（数据层，真正病因）：MinIO 没有对应的 HLS 文件

`G:\data\project\edamlog\error_202610201519.txt` 第 4~11 行：

```
ERROR c.e.edam.controller.PlaybackController  : m3u8_serve_failed, video_id=28
io.minio.errors.ErrorResponseException: The specified key does not exist.
```

后端 `getPlaylist` 向 MinIO 拉
`videos/28/hls/playlist.m3u8`，**MinIO 返回 key 不存在**。
`catch (Exception e)` 把异常吞掉，返回 404 → hls.js 当成
NETWORK_ERROR fatal。

worker 端上传路径一致（`worker/src/edam_worker/processors/video.py:71`），
所以这条视频的 HLS 处理链路**确实没把文件落到 MinIO**：可能 worker
当时未运行 / 处理失败 / RabbitMQ 队列里任务被消费丢了。
**这一层是数据问题，前端无法绕过；要彻底复播得让 worker 重跑这条
记录（最简单是重新上传一次）**。

### 根因 2（前端，上一轮 f55284a fix 的副作用）：重试无上限 + 错误信息被吞

上一轮 HLS 修复给 NETWORK_ERROR 致命错误加了 `hls.startLoad()` 重试，
但**没有上限**。在 `manifest` 持续 404 这种"永久失败"场景下：

- hls.js 抛 fatal NETWORK_ERROR
- 我的 handler 调 `startLoad()`
- 立刻又是 fatal NETWORK_ERROR
- 永远循环，用户看到的就是一直转圈的"正在重试…"
- 真实的 HTTP 状态码（这里是 404）从来没被用户看到

## 修复

### VideoDetail.vue 错误处理器

- 引入 `NETWORK_MAX_RETRIES = 3` 上限，达到上限后落 fallback 之前
  **暴露真正的 HTTP 状态**给用户；404 时还特别说明"视频文件在对象
  存储中不存在，请联系管理员重新处理"，因为这就是数据层那个根因。
- `networkRetries` 与 `recoveryAttempted` 解耦：网络错有自己的计数，
  媒体错（`recoverMediaError`）只标志位一次。
- 错误信息带上进度："网络异常，正在重试（1/3）…(HTTP 404)"
- NETWORK 重试耗尽 + 媒体错尝试耗尽 + 其它类型 = 落 fallback（保留
  上一轮 destroy-first 修复，防止 `mediaSourceRequiresReset` 级联）

```ts
const NETWORK_MAX_RETRIES = 3;
let networkRetries = 0;
let recoveryAttempted = false;

hls.value.on(Hls.Events.ERROR, (_e, data) => {
  if (!data.fatal) return;
  if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
    const status = data.response?.code;
    if (networkRetries < NETWORK_MAX_RETRIES) {
      networkRetries++;
      errorMsg.value = `网络异常，正在重试（${networkRetries}/${NETWORK_MAX_RETRIES}）…` +
        (status ? ` (HTTP ${status})` : '');
      try { hls.value?.startLoad(); return; } catch { /* fall through */ }
    }
    errorMsg.value =
      `视频源不可达 (HTTP ${status ?? '??'})：` +
      (status === 404
        ? '视频文件在对象存储中不存在，请联系管理员重新处理'
        : '请检查网络或稍后重试');
  } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
    if (!recoveryAttempted) {
      recoveryAttempted = true;
      try { hls.value?.recoverMediaError(); return; } catch { /* fall through */ }
    }
    errorMsg.value = `视频解码失败 (${data.details})：已切换至兼容模式`;
  }
  // 落 fallback（destroy-first，与上一轮叠加）
  try { hls.value?.destroy(); hls.value = null; ... } catch {}
});
```

## 数据层处理（不在本次代码改动内）

按 video_id 反查 MinIO，确认 `videos/28/hls/playlist.m3u8` 等文件是否
真的缺失；如果是，让 worker 重新跑该条：

- 上传新版本（覆盖原 video_id 的 minio_path）→ worker 自动接力
- 或在 worker 端临时加 "重跑" 任务，直接投递 RabbitMQ 任务
  （本次没动 worker）

## 验证

1. 故意把后端 `getPlaylist` 临时改成 `throw new RuntimeException("test")`
   重启，访问任一视频详情页：
   - 期望看到 "网络异常，正在重试（1/3）…(HTTP 404)" → 2/3 → 3/3
   - 3 次后看到 "视频源不可达 (HTTP 404)：视频文件在对象存储中不存在，
     请联系管理员重新处理"，并切原生 `<video>`（同样会卡在自己上）
2. 故意断网（停后端）后访问：
   - 期望看到 "视频源不可达 (HTTP ??)/请检查网络或稍后重试"
3. 正常视频：首次播放正常工作，重试机制不应被触发

按本项目 git-auto-commit 约定自动 commit + push。
