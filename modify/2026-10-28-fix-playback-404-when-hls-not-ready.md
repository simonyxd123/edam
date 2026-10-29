# 播放报 404：其实是"视频还没转码完"，不是网络问题

- 日期：2026-10-28
- 触发：视频点播放报 `网络异常，正在重试（1/3）… (HTTP 404)`

---

## 一、现象与实测

前端点播放 → Hls.js 拉 m3u8 拿到 404 → 报"网络异常，正在重试（1/3）… (HTTP 404)"。

在服务器上实测（`192.168.10.2`）：

| 检查项 | 结果 |
|---|---|
| `GET /api/v1/videos/1` | `hls_status=pending`、`hls_path=null`、`duration_sec=0` |
| `GET /api/v1/playback/1/playlist.m3u8`（带合法 token） | **HTTP 404** |
| MinIO `edam-videos` 里 `videos/1/` 前缀 | **0 个对象** |
| MinIO `edam-videos` 全 bucket | 只有 `uploads/2/d516c747-….mp4`（原始 mp4） |
| `/api/v1/health/ready` | `rabbitmq.queue_depth = 1` |

**结论：404 是"MinIO 上确实没有这个对象"，不是网络故障。**
`videos/1/hls/` 目录压根不存在 —— 视频**从没被转码过**。

而 `queue_depth=1` 说明：转码任务**躺在 RabbitMQ 队列里没人消费** ——
Worker 进程没起来（服务器上 8001 端口是关的，`/health/worker` 探测不到）。

**真正的根因是 Worker 没跑**，404 只是它的表象。

---

## 二、为什么用户看到的是"网络异常"

三处叠加，把"还没转码"说成了"网络异常"：

**1）前端不判断转码状态就开播**

`VideoDetail.vue` 的 `loadVideo()` 拿到视频元数据后**直接**去拉 token + m3u8，
从不看 `hls_status`。转码没完成时 m3u8 必然 404。

**2）后端把所有异常一律报 404**

`PlaybackController.getPlaylist` 的 catch 块：

```java
} catch (Exception e) {
    log.error("m3u8_serve_failed, video_id={}", videoId, e);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
}
```

MinIO **连不上**（网络/凭据/容器挂了）和**对象不存在**报同一个码，
运维看日志会被带偏去查"文件是不是被删了"，而真正该查的是 MinIO 连通性 / Worker 存活。

**3）前端把 404 翻译成"网络异常"**

Hls.js 只知道"拿不到"，统一按 NETWORK_ERROR 处理 →
`网络异常，正在重试（1/3）… (HTTP 404)`。404 重试 3 次必然还是 404 ——
纯浪费，而且把用户引向错误的方向（查网络）。

---

## 三、修复

### 1）后端：404 与 503 分开（`PlaybackController`）

- 对象不存在（MinIO 明确回 `NoSuchKey`/`NoSuchBucket`）⇒ **404**
- MinIO 不可达（连接类异常 / 其他 S3 错误码）⇒ **503**

前端据此给出不同的提示，运维也能从状态码直接判断该查转码流程还是查存储连通性。
OpenAPI 契约同步补上 404/503 的说明。

### 2）前端：先看转码状态，再决定要不要拉 m3u8（`VideoDetail.vue`）

- `hls_status` 是 `pending`/`processing` ⇒ **不拉 m3u8**，
  提示"视频正在转码处理中，完成后本页面会自动开始播放"，
  并**轮询**（3s 一次，最长 10 分钟），转码完成后自动开播。
- `hls_status` 是 `failed` ⇒ 明确提示"视频转码失败"，不再伪装成网络问题。

轮询逻辑与列表页 `Videos.vue` 已有的 HLS 轮询同源（同一个后端字段、同样的 3s 间隔）。

### 3）前端：404 / 503 的提示改准确

- 404 ⇒ "视频尚未转码完成，或播放产物在对象存储中不存在"
- 503 ⇒ "对象存储暂时不可用，请稍后重试或联系管理员"
- 其它 ⇒ 保持"请检查网络或稍后重试"

---

## 四、验证

- `PlaybackControllerTest`（新增 3 个用例，全绿）
  - 对象不存在 ⇒ 404
  - MinIO 不可达 ⇒ 503
  - token 无效 ⇒ 401
- 后端全量 61 个用例，只有 `ApplicationStartupTest` 的 5 个红（本机没 MySQL，
  已用 `git stash` 在未修改的原始代码上复跑确认同样红）。
- 前端：`vue-tsc --noEmit` 干净；`vitest run` 20 个用例全过。
- OpenAPI：`yaml.safe_load` 通过，playlist 端点 responses = 200/401/404/503。
- 实测服务器上 `GET /api/v1/playback/1/playlist.m3u8` 确实返回 404，
  与用户报的错误完全一致 —— 复现路径已闭环。

---

## 五、遗留：Worker 没起（运维动作，非代码问题）

服务器上 Worker 进程是手动起的（`python3 -m uvicorn edam_worker.main:app --reload --port 8001`），
这次没起来 → 任务卡在队列 → 视频永远停在 `pending`。

**恢复办法**（在服务器上执行）：

```bash
cd /var/data/project/edam/worker/src
nohup python3 -m uvicorn edam_worker.main:app --reload --port 8001 &
```

队列是 durable 的、消息是 PERSISTENT 的，**Worker 一起来就会自动消费掉那条积压任务**，
不需要重新上传视频。起来后用 worker 自检确认：

```bash
curl -s http://127.0.0.1:8001/health/worker | python3 -m json.tool
```

`hls_status` 会从 `pending` → `ready`，MinIO 上出现 `videos/1/hls/playlist.m3u8`，视频即可播放。

> 注：Worker 手动起、没有进程守护（systemd / supervisor / K8s），
> 机器重启或进程被 OOM kill 就会再次卡住。K8s 侧已有 `worker-deployment.yaml`，
> dev 环境这边建议加个 systemd unit 或 supervisord，否则这个坑会反复出现。
