# 修复播放时 HLS `mediaSourceRequiresReset` 级联报错

- 日期：2026-10-20
- 影响：web/src/views/VideoDetail.vue
- 现象：首播或切集时控制台循环刷出
  `HLS mediaError/mediaSourceRequiresReset: MediaSource closed while media attached - triggering recovery`

## 根因

`VideoDetail.vue` 的 HLS 致命错误回退路径在 hls.js 实例仍然持有自己
`new MediaSource()` 并已 `attachMedia(videoEl)` 的状态下，直接执行
`videoEl.value.src = m3u8Url.value` 覆盖 `<video>` 的当前资源。

浏览器会立刻走 HTMLMediaElement 的 source selection 算法，把已 attach
的 `MediaSource` 状态强制改成 `closed`。hls.js 内部的 MediaSource monitor
检测到 "closed while attached" 后打日志触发恢复动作
（`mediaSourceRequiresReset`），又开始新一轮加载——而此时它刚被强制
关闭的同一个源仍然恢复不回来，于是错误流循环输出，并表现为视频卡死。

## 修复

1. **致命错误回退前先 destroy**（`web/src/views/VideoDetail.vue`）
   ```ts
   if (data.fatal) {
     try {
       hls.value?.destroy();   // 先断开内部 MediaSource
       hls.value = null;
       // 清掉 hls.js 写入的 src / ObjectURL 再赋值，避免残留资源被回收冲突
       videoEl.value.removeAttribute('src');
       videoEl.value.load();
       videoEl.value.src = m3u8Url.value;
     } catch (e) { /* noop */ }
   }
   ```
2. **onBeforeUnmount 同步加固**：destroy 后置空 `hls.value`，并对
   `videoEl` 做 `pause() + removeAttribute('src') + load()`，避免
   异步回调（如后台触发的 ERROR 事件）落到已销毁实例上。

## 触发条件复盘

- `enabledWorker: true` 下 worker 报错 → hls.js 上抛 fatal
- 原代码 `videoEl.src = m3u8Url.value` 把 MediaSource 强制关掉
- hls.js 重新尝试挂载同一个 URL → 又抛同样的 fatal → 死循环

修复后回退路径变成「先安全 detach，再走原生 HLS」，符合 hls.js 1.5
要求的 lifecycle 顺序。

## 跟进：去除 autoplay 隐患

模板里 `<video autoplay muted>` 在 hls.js 还没 attach 时浏览器就已经
尝试 play；source 为空时部分实现会把 MediaSource 推进 `ended`/`empty`，
后续 attach 后又触发一轮 source-change 事件，与 hls.js 的内部状态机
打架，被误归为 MEDIA_ERROR。

改为：**移除 HTML `autoplay`，改在 `Hls.Events.MEDIA_ATTACHED` 之后
再 `play()`**，并 catch 浏览器 autoplay 拒绝（`NotAllowedError`），
行为等同自动播放但时序安全。

```ts
hls.value.on(Hls.Events.MEDIA_ATTACHED, () => {
  if (!videoEl.value) return;
  videoEl.value.muted = true;
  const p = videoEl.value.play();
  if (p && typeof p.catch === 'function') {
    p.catch((err) => console.warn('autoplay blocked, waiting user gesture:', err?.name || err));
  }
});
```

## 跟进：错误按类型分流（NETWORK → startLoad，MEDIA → recoverMediaError）

原实现一遇 fatal 就立刻把 `<video>.src = m3u8Url` 切原生，但这在 EDAM
这种 secure_link + AES key 鉴权链下基本没用：

- **NETWORK_ERROR**（manifest/segment 4xx/5xx、secure_link 拒签、AES key 拉取失败）：
  native `<video>` 同样会触发同样的请求，结果一样失败
- **MEDIA_ERROR**（decrypt/append/buffer-full）：hls.js 自带 `recoverMediaError()` 通常能救回来
- **KEY_SYSTEM_ERROR / MUX_ERROR / REMUXING_ERROR / OTHER_ERROR**：
  不可恢复，且 native 几乎不可能更好 → 走 fallback

修改后（伪码）：

```ts
let recoveryAttempted = false;
hls.value.on(Hls.Events.ERROR, (_e, data) => {
  if (!data.fatal) return;                            // 非致命：hls.js 内部已重试
  if (!recoveryAttempted) {
    recoveryAttempted = true;
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
      try { hls.value?.startLoad(); return; } catch {}
    } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
      try { hls.value?.recoverMediaError(); return; } catch {}
    }
  }
  // 二次 fatal / 不可恢复类型 → 切原生 <video>（保持上次 destroy-first 修复）
  hls.value?.destroy(); hls.value = null;
  videoEl.value?.removeAttribute('src'); videoEl.value?.load();
  if (videoEl.value) videoEl.value.src = m3u8Url.value;
});
```

要点：

1. `recoveryAttempted` 用闭包变量，仅一次尝试 → 防止恢复失败再次进
   ERROR 处理器时仍走 fallback 再触发 `mediaSourceRequiresReset` 死循环
2. NETWORK_ERROR 走 `startLoad()`，比 fallback 更经济（不必重建 MediaSource）
3. MEDIA_ERROR 走 `recoverMediaError()`，这是 hls.js 1.5 文档化的恢复入口
4. fallback 路径仍然先 `destroy()` 再覆盖 `videoEl.src`，与上一轮修复叠加

## 验证

1. **autoplay + 首播冒烟**：开 DevTools，直接进入视频详情页，确认
   `MEDIA_ATTACHED` 事件触发后视频自动开始；无 console warning
   `autoplay blocked`，无 `MEDIA_ERROR` 风暴
2. **NETWORK_ERROR 恢复**：DevTools Network → Throttling Slow 3G，让
   manifest 第一次必然 5xx；确认：先看到 `errorMsg` 变"网络异常，
   正在重试…"，随后一次 `startLoad()` 自动恢复播放，不应切到原生
3. **MEDIA_ERROR 恢复**：手动把后端 `output.m3u8` 改成含坏 init
   segment，确认：先看到"解码异常，正在恢复…"，随后一次
   `recoverMediaError()` 自愈，不应走到 fallback
4. **不可恢复 → fallback**：断网 + BadToken，确认：第二次 fatal 时
   才切到原生 `<video src>`，并打印 `fell back to native <video src>`
   （第一次仍尝试 startLoad）
5. **彻底不再级联**：上述每条场景下，DevTools console 都不应再出现
   反复刷屏的 `mediaSourceRequiresReset`

未自动 git 提交（按 CLAUDE.md 约定）。
