# 视频转码被 broker 掐断 —— consumer_timeout 事故复盘与整改

> 日期：2026-11-10
> 触发：用户反馈"视频转码报错"，提供 worker 日志
>   `C:\Users\yuxd\Desktop\fsdownload\log_202611100920.txt`
> 方案书章节：§4.2 视频 HLS 切片与加密 / §7.1 核心数据表（video_resource）
>   / §14.3 STRIDE / Denial of Service（资源耗尽型）

---

## 0. 先说日志里到底有什么

日志只有 36 行，全部是 worker 的 systemd journald 输出。关键片段：

```
Nov 09 21:38:10 {"video_id": 14, "event": "video_processing_start"}
Nov 09 21:50:52 {"video_id": 14, "event": "video_processing_complete"}
Nov 09 21:57:14 {"video_id": 15, "event": "video_processing_start"}
Nov 09 22:27:14 {"video_id": 15, "status": 200, "event": "notify_backend_ok"}
Nov 09 22:27:14 Exception in callback <function _task_done at 0x7f650d932af0>
  File ".../main.py", line 349, in handle_video -> await self._process_with_retry(
  File ".../main.py", line 108, in _process_with_retry -> await processor_fn(payload)
  File ".../processors/video.py", line 83, in process -> await self._hls_transcode(...)
  File ".../processors/video.py", line 395, in _hls_transcode -> await asyncio.wait_for(
  File "/usr/lib/python3.9/asyncio/tasks.py", line 468, in wait_for -> await waiter
asyncio.exceptions.CancelledError
During handling of the above exception, another exception occurred:
  File ".../aio_pika/message.py", line 582, in __aexit__ -> if not self.message.channel.is_closed:
  File ".../aio_pika/message.py", line 391, in channel -> raise ChannelInvalidStateError
aiormq.exceptions.ChannelInvalidStateError
```

**两个视频，同一个 worker，一个成功一个失败** —— 这是最关键的对照：

| video_id | 开始 | 结束 | 耗时 | 结果 |
|---|---|---|---|---|
| 14 | 21:38:10 | 21:50:52 | **762 秒（12.7 分钟）** | ✅ 成功 |
| 15 | 21:57:14 | 22:27:14 | **1800 秒（正好 30 分钟）** | ❌ 被掐断 |

**1800 秒 = 30 分钟 = RabbitMQ 未确认投递超时的默认值。**

---

## 1. 根因：RabbitMQ 的 consumer_timeout 掐断了 channel

### 1.1 机制

RabbitMQ 有一条规则：**一条消息投递出去之后，如果超过 `consumer_timeout`
还没被 ack（确认），broker 就认为"这个消费者卡死了"，于是直接掐断 channel**
（`PRECONDITION_FAILED`）。

默认值 **1800000 ms = 30 分钟**。

### 1.2 为什么是 CancelledError 而不是别的异常

channel 被 broker 掐断 ⇒ AMQP 库取消正在跑的 consumer 协程 ⇒
协程里正在 await 的地方抛出 `asyncio.CancelledError`。

日志里它出现在 `video.py:395` 的 `asyncio.wait_for(proc.communicate(), ...)`
里 —— 也就是**正在等 FFmpeg 跑完**的那一刻。

### 1.3 三个致命的连锁反应

**① `except Exception` 抓不到它**

Python 3.8 起 `asyncio.CancelledError` 继承的是 **`BaseException`**，
不是 `Exception`。worker 原来只写了 `except Exception`：

```python
except Exception as e:
    failed = True
    log.error("video_processing_failed", ...)
    raise
```

⇒ **这个分支完全没进**。后果：

- 没有 `video_processing_failed` 日志（日志里查无此行，可验证）
- `failed` 仍是 `False`
- Prometheus 的 `worker_tasks_processed_total{result="failed_permanent"}` 不动
- `/health/worker` 的 `last_failure_*` 不更新

⇒ **一次被 broker 掐死的转码，在监控上完全隐形。**

**② 善后失败顶替了真正的失败原因**

`message.process()` 的 `__aexit__` 要 ACK/reject 消息，但 channel 已经死了：

```
aiormq.exceptions.ChannelInvalidStateError
```

这个异常**替换**了原本的 `CancelledError` 往上抛。
日志里"During handling of the above exception, another exception occurred"
那一句就是它。⇒ 排障时看到的是一句没头没尾的
"channel invalid state"，而"是谁掐了我们"这条关键线索被吞掉了。

**③ FFmpeg 变成孤儿进程**

协程被取消时，`await proc.communicate()` 抛 CancelledError，
但**子进程还活着** —— FFmpeg 继续吃 CPU，而 worker 侧看不到任何异常。
（取消中的协程也不能再 `await kill_process()`，会立刻被再次取消。）

### 1.4 为什么 video 14 没事、video 15 有事

video 14 转码 12.7 分钟 < 30 分钟 → 正常 ACK。
video 15 转码超过 30 分钟 → 被掐断。

**这不是"video 15 坏了"，是"video 15 比 30 分钟更慢"。**
大视频转码几十分钟是常态（8GB 上限的下载 + HLS 切片 + 帧指纹 + 上传）。

---

## 2. 顺带查出来的第二个缺陷：告警是条死规则

`monitoring/grafana/alerts/edam-rules.yaml`：

```yaml
- alert: VideoTranscodeFailure
  expr: |
    sum(rate(worker_tasks_processed_total{task_type="video",result="failed"}[10m]))
```

而代码里发出的 label 值是 **`failed_permanent`**（见 `worker/src/edam_worker/metrics.py`），
**从来没有 `failed` 这个值**。

⇒ 这个告警**永远不会触发**。
⇒ 就算代码把失败记进去了，监控也不会响。

**这次事故正是被这条死规则漏掉的** —— 转码失败率一直显示 0%。

---

## 3. 整改

分三层：**broker 侧改配置**（治本）、**worker 侧加闸门**（治标但自愈）、
**worker 侧让失败可见**（防止再隐形）。

### 3.1 broker 侧：调大 consumer_timeout

| 位置 | 改动 |
|---|---|
| `dev/rabbitmq/rabbitmq.conf`（新增） | `consumer_timeout = 14400000`（4 小时），挂进 compose |
| `dev/docker-compose.yml` | 挂载上面这个配置文件 |
| `ops/rabbitmq/README.md`（新增） | **生产环境的操作手册**：按 RabbitMQ 版本分别给做法（3.8~3.11 节点级配置 / 3.12~4.2 用 `consumer-timeout` 策略 / 4.3+ 只支持 quorum 队列），以及验证步骤 |

生产环境的 RabbitMQ **不在本仓库的 Helm chart 里**
（`helm/edam/values.yaml` 里 `RABBITMQ_HOST: rabbitmq.edam.svc.cluster.local`，
由外部部署），所以生产侧必须由运维按 `ops/rabbitmq/README.md` 手工执行。

### 3.2 worker 侧：自己掐表（`WORKER_TASK_DEADLINE_SEC`）

光调大 broker 的超时是治标 —— broker 的配置不在仓库里，
运维改一次、漂移一次就又出事了。

worker 现在有**自己的**任务墙钟上限（默认 3 小时），
超时由 worker 主动触发，拿到的是一个**干净、可诊断、可告警**的失败
（`TaskDeadlineExceeded`），而不是被 broker 从背后掐断后的隐形失败。

**关键细节：预算是"整条消息"的，不是"每次尝试"的。**
broker 的未确认投递超时覆盖的是消息从投递到 ack 的**全部**时长
（含全部重试与退避）。如果每次尝试各有一份预算，
3 次 × 1 小时 = 3 小时，而 broker 只给 30 分钟 ⇒ 第二次尝试照样被掐断。

新增 `worker/src/edam_worker/config.py`：

```python
WORKER_TASK_DEADLINE_SEC = 10800      # 3 小时，整条流水线的墙钟上限
RABBITMQ_CONSUMER_TIMEOUT_SEC = 14400 # 4 小时，"我以为 broker 是多少"
```

并在启动时自检三档超时的顺序（`timeout_budget_warnings()`）：

```
consumer_timeout (4h) > WORKER_TASK_DEADLINE_SEC (3h) > FFMPEG_TIMEOUT_SEC (1h)
```

不自洽就启动时打 `worker_timeout_budget_invalid` 错误日志 ——
**这个坑不能等到"某个大视频转了 30 分钟然后消失"才发现**。

实现上用 `asyncio.wait` 而不是 `asyncio.wait_for`：
后者靠**异常类型**区分"超时"和"内部抛的 TimeoutError"，
而 Python 3.11+ 起 `asyncio.TimeoutError` 就是内置 `TimeoutError`，
`socket.timeout` 也是它 ⇒ MinIO 的一次网络抖动会被误报成"任务超时"
（= 永久失败 = 不重试），而它本来是个该重试的瞬时故障。
`asyncio.wait` 按"跑完了没有"判定，没有这种歧义。

### 3.3 worker 侧：让取消不再隐形

**`worker/src/edam_worker/processors/video.py` / `document.py`**

显式接住 `asyncio.CancelledError`：标记失败、打日志、
让 `finally` 把 failed 状态通知给后端（前端能看到"处理失败"而不是永远转圈），
然后**继续向上抛**（取消语义必须保留，否则任务会被误记成成功）。

**`worker/src/edam_worker/main.py`**

- `_process_with_retry` 里区分两种取消：
  - **自己优雅关闭**（SIGTERM）⇒ 不是失败，不记（否则每次滚动重启都刷一堆失败，把真信号淹掉）
  - **broker 掐断 channel** ⇒ **是失败**，必须记：更新 `/health/worker` 的 `last_failure_*`、
    Prometheus 记 `result="cancelled"`、打错误日志
- `handle_video` / `handle_document`：把善后阶段的 `ChannelInvalidStateError`
  换回**真正的失败原因**（`__context__` 里的那个），不让它顶替

**`worker/src/edam_worker/procutil.py`**

新增 `kill_process_nowait()`：取消中的协程不能 `await`，
所以用 `terminate()` + `loop.call_later` 补一个"宽限后强制 kill"。

**`worker/src/edam_worker/processors/video.py` / `document.py`**

FFmpeg / LibreOffice 在被取消时调用它 ⇒ 不再留吃 CPU 的孤儿进程。

### 3.4 监控

- `monitoring/grafana/alerts/edam-rules.yaml`
  - 修掉死规则：`result="failed"` → `result=~"failed_permanent|cancelled"`
  - 新增 `WorkerTaskCancelledByBroker` 告警（专门盯"被 broker 掐断"）
- `worker/src/edam_worker/metrics.py`
  - `result` 的取值补上 `cancelled` 并写清语义

---

## 4. 验证结果

| 项 | 结果 |
|---|---|
| Worker 测试 | 116 用例 / 0 失败（新增 `test_task_cancellation.py` 13 条；2 条需 ffmpeg / PyPDF2 跳过） |
| 后端 / 前端 | 未改动，无需重跑 |
| 端点对账 | 99 ↔ 99 / 100%（未改端点） |

新增用例覆盖：

- `CancelledError` 不被 `except Exception` 漏掉（记失败 + 通知后端 failed）
- **真的 `task.cancel()`** 下，后端回调仍然发得出去
  （手动 raise 测不出"取消中的协程还能不能 await"，而线上恰恰是 task.cancel() 触发的）
- **任务上限触发时**后端也收到 failed 回调（前端不会永远转圈）
- 取消一路传到 `_process_with_retry`（不能被吞 —— 吞了消息会被 ACK 掉）
- 被 broker 掐断 ⇒ 记进 `last_failure_*`；自己关闭 ⇒ **不**记
- 任务上限 ⇒ `TaskDeadlineExceeded`（永久失败，**不重试** —— 同样的输入再跑还是超）
- 内部抛的 `TimeoutError`（socket 超时）**不会**被误判成"任务超时"
  （否则一次该重试的网络抖动会被误报成永久失败）
- 预算是**跨尝试共享**的（总耗时被预算兜住）
- 超时预算自检能发现"任务上限 >= broker 超时"
- 转码被取消 ⇒ FFmpeg 子进程确实被杀

---

## 5. 还需要人工做的事

**生产环境的 RabbitMQ 必须手工改配置** —— 它不在本仓库里。

按 `ops/rabbitmq/README.md` 执行，并同步改 worker 的
`RABBITMQ_CONSUMER_TIMEOUT_SEC`（worker 用它自检，
写错会让启动自检失去意义或误报）。

**部署顺序**：先改 broker（调大超时）→ 再改 worker（代码 + 配置）→ 最后重启 worker。
反过来的话，broker 还是 30 分钟的话，新代码只是把"隐形失败"变成"可见失败"，
视频仍然转不出来。

**K8s 环境**：`helm/edam/values.yaml` 的 `worker.env` 已加三档超时；
已核对 `gitops/overlays/{dev,staging,prod}/values.yaml` ——
**overlay 没有覆盖 `worker.env`**，base 的新值直接生效。

---

## 6. 已知限制

- **broker 的超时是外部配置，仓库管不住**。worker 侧的任务上限是兜底，
  不是替代 —— 它保证失败可见、可诊断，但视频能不能转出来，
  取决于 broker 的超时够不够。
- **`consumer_timeout` 在 RabbitMQ 4.3+ 只支持 quorum 队列**。
  EDAM 的队列目前是 classic（`declare_queue(durable=True)`，没声明队列类型）。
  4.3+ 上要么把队列改成 quorum（要删旧队列），要么接受 classic 没有投递超时
  —— 此时 worker 侧的任务上限就是唯一的闸门。
  详见 `ops/rabbitmq/README.md` 第 3.3 节。
- **8GB 视频 + 4 小时上限**：够不够用取决于实际转码速度。
  3 小时的任务上限对超长视频可能仍然偏紧（实测 video 15 就超过了 30 分钟）。
  真需要更长的视频，应同时调大两处。
