# Worker 自愈（A：失败重试 + B：自检端点）

- 日期：2026-10-20
- 影响：worker/src/edam_worker/main.py、worker/src/edam_worker/config.py
- 背景：从 e222b42 / afb712f 一路追下来，我们已经能在前端暴露
  HTTP 404、在 worker 端有 OPS 接口重新触发 HLS；但**根因未消除**——
  当 worker 真出故障时：
  - 处理异常被 `requeue=False` 静默丢消息
  - 没有"最近一次任务成败"的可见性
  - 运维只能靠 docker logs 翻，定位一条失败任务很慢

本次做两件事。

## A. 处理失败：内部 N 次重试 + loud error

新增 `WorkerApp._process_with_retry(task_type, payload, processor_fn)`：
- 走 `settings.WORKER_MAX_ATTEMPTS`（默认 3）+ 指数退避（1s, 2s, 4s, …）
- 任意一次抛错先在 `worker_task_attempts_total{attempt_result="failed"}`
  +1；如果还没用尽 attempt 数就 warn 并 sleep 重试
- 用尽后：
  - 在 `worker_tasks_processed_total{result="failed_permanent"}` +1
  - 写一行 **loud error** 日志，包含 payload + 终态错误类型 / 消息
  - 更新 `_last_task_state[task_type]` 供 `/health/worker` 暴露
- 抛回最后异常 → 外层 `message.process(requeue=False)` 仍按原
  NACK 策略丢消息，**避免在队列里"假活"占位**

handlers（`handle_video/document/watermark`）改成 parse + 调
`_process_with_retry`。payload 不合法的情况独立报错 + ACK，不再
被反复重试无意义消息。

**可调环境变量：**
```
WORKER_MAX_ATTEMPTS=3          # 1 = 与原"一次性"语义一致
WORKER_RETRY_BASE_BACKOFF_SEC=1.0
```

## B. /health/worker：worker 真活与否一眼看清

新增 `GET /health/worker`（不需要 token），聚合：

| 字段 | 来源 |
|---|---|
| `rabbitmq.ok` | `worker.connection.is_closed` 是否 False |
| `minio.ok`   | `Minio.bucket_exists(videos)` 是否 True |
| `backend.ok` | `GET <BACKEND_BASE_URL>/actuator/health/liveness` 5xx 之前都算 ok |
| `task_state` | `_record_task` 累计的 `{task_type: {last_success_*, last_failure_*}}` |
| `overall_ok` | 三类依赖全 ok |

返回总是 200（不靠 HTTP 状态区分），可同时喂 curl / Prometheus
blackbox / K8s 自定义探针。

### 示例响应

```json
{
  "overall_ok": false,
  "ts": "2026-10-20T16:08:33",
  "rabbitmq": {"ok": true, "host": "10.0.0.5", "port": 5672},
  "minio":    {"ok": true, "endpoint": "http://10.0.0.7:9000", "bucket_videos": "edam-videos"},
  "backend":  {"ok": true, "status": 200, "url": "http://10.0.0.6:8092/actuator/health/liveness"},
  "task_state": {
    "video": {
      "last_success_at": "2026-10-20T15:45:01",
      "last_success_video_id": 27,
      "last_failure_at": "2026-10-20T15:45:06",
      "last_failure_video_id": 29,
      "last_failure_error_type": "ErrorResponseException",
      "last_failure_error_message": "The specified key does not exist."
    },
    "document": {"last_success_at": "2026-10-20T15:30:12"},
    "watermark": {}
  }
}
```

看 `task_state.video.last_failure_video_id: 29` + `last_failure_error_type`
即可直接定位刚才复发的"MinIO key 缺失"——无需翻日志。

## Prometheus 新指标

- `worker_task_attempts_total{task_type, attempt_result}` —
  `failed` 在每一次重试失败时 +1，`success` 暂不增，避免双计
- `worker_tasks_processed_total{result=failed_permanent}` 取代
  旧的 `failed` —— 旧监控如果不区分，新标签值会出现在时间序列里，
  现有 alert / dashboard 可平滑过渡

`worker_tasks_processed_total{result=success}` 行为不变。

## 与已有修复的联动

| 故障 | 之前 | 现在 |
|---|---|---|
| worker 处理偶发抖动（瞬时网络） | 消息直接 NACK 丢弃 | 内部重试 3 次救回来，最终成功时只在日志里 info |
| worker 处理永久失败（MinIO key 缺） | 消息丢；日志里只 `video_task_failed error=...` | 消息丢；日志里多一行 `video_task_failed_permanent` 含 `max_attempts=3 final_error_type=...`，且 `/health/worker` 立刻看到 `task_state.video.last_failure_*` |
| 不知道 worker 真不真在干活 | `ps` + `journalctl` 翻 | `curl :8001/health/worker` 一次返回 |

按本项目 git-auto-commit 约定自动 commit + push。
