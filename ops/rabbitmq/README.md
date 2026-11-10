# RabbitMQ 未确认投递超时（consumer_timeout）配置手册

> 触发：**2026-11-10 视频转码中途消失事故**
> 现象：视频转码跑到一半就没了，日志里只有一条看不懂的
> `ChannelInvalidStateError`，前端提示"处理失败"，监控上**完全没有告警**。

---

## 一、先说清楚这个坑是什么

RabbitMQ 有一条规则：**一条消息投递出去之后，如果超过 `consumer_timeout`
还没被 ack（确认），broker 就认为"这个消费者卡死了"，于是直接掐断 channel。**

后果链条：

```
consumer_timeout 到点（默认 30 分钟）
  └─ broker 掐断 channel（PRECONDITION_FAILED）
       └─ 正在跑的 consumer 协程被连带取消
            └─ 异常以 asyncio.CancelledError 的形式冒出来
                 └─ Python 3.8+ 里 CancelledError 继承 BaseException
                      └─ `except Exception` 抓不到它
                           └─ 不打失败日志、不进 Prometheus、
                              /health/worker 不更新
                                └─ 这次转码失败在监控上完全隐形
```

**为什么 EDAM 一定会踩到**：视频转码流水线是
「下载（8GB 上限）→ FFmpeg HLS 切片 + AES 加密 → 帧指纹提取 → 上传 MinIO」，
大视频跑几十分钟是常态，而 broker 默认只给 30 分钟。

**为什么监控上看不见**：`CancelledError` 继承的是 `BaseException`，
不是 `Exception`。worker 原来只写了 `except Exception`，
所以那次失败既没进 `except` 分支（没打 `video_processing_failed`），
也没进 Prometheus 计数器 —— 失败率永远是 0。

---

## 二、三个超时必须自上而下递减

| 层 | 配置项 | 默认值 | 管什么 |
|---|---|---|---|
| broker | `consumer_timeout` | 1800000 ms（30 分钟） | 消息投递到 ack 的最长允许时间 |
| worker | `WORKER_TASK_DEADLINE_SEC` | 10800 s（3 小时） | 整条流水线（下载+转码+指纹+上传）的墙钟上限 |
| worker | `FFMPEG_TIMEOUT_SEC` | 3600 s（1 小时） | 单个 FFmpeg 子进程的上限 |

**顺序必须是**：`consumer_timeout` > `WORKER_TASK_DEADLINE_SEC` > `FFMPEG_TIMEOUT_SEC`。

worker 启动时会自检这层关系，不自洽就打
`worker_timeout_budget_invalid` 错误日志（见 `worker/src/edam_worker/config.py`
的 `timeout_budget_warnings()`）。

**为什么 worker 也要自己掐表**：光调大 broker 的超时是治标 ——
broker 的配置不在这个仓库里，运维改一次、漂移一次就又出事了。
worker 自己有一个更短的上限，就能保证**超时由 worker 触发**，
拿到的是一个干净的、可诊断、可告警的失败（`TaskDeadlineExceeded`），
而不是被 broker 从背后掐断channel 后那种隐形失败。

---

## 三、按 RabbitMQ 版本配置

先确认版本：

```bash
rabbitmqctl status | grep -i "RabbitMQ version"
# 或
docker exec edam-rabbitmq rabbitmqctl status | grep -i "RabbitMQ version"
```

### 3.1 RabbitMQ 3.8 ~ 3.11 —— 节点级配置

改 `/etc/rabbitmq/rabbitmq.conf`（容器里是 `/etc/rabbitmq/rabbitmq.conf`）：

```ini
consumer_timeout = 14400000
```

然后**重启 broker**：

```bash
systemctl restart rabbitmq-server
# 容器：
docker restart edam-rabbitmq
```

> ⚠️ 改节点级配置要重启 broker ⇒ 期间 worker 连不上、队列积压。
> 请在变更窗口里做（见 `ops/sop/04-change-management.md`）。

### 3.2 RabbitMQ 3.12 ~ 4.2 —— 推荐按队列配

3.12 起官方推荐按队列配置，**不用重启 broker**，而且只影响 EDAM 的队列。

**方式 A：队列参数**（改 worker 的队列声明）——
在 `worker/src/edam_worker/main.py` 的 `declare_queue` 加参数：

```python
queue = await self.channel.declare_queue(
    queue_name,
    durable=True,
    arguments={"x-consumer-timeout": 14400000},   # 4 小时
)
```

> ⚠️ **加参数会让已存在的同名队列声明不等价** ⇒ broker 报
> `PRECONDITION_FAILED - inequivalent arg 'x-consumer-timeout'`，
> worker 起不来。**必须先删掉旧队列**（队列里的消息会丢），
> 或者走方式 B。

**方式 B：策略**（推荐 —— 不改代码、不删队列、不重启 broker）：

```bash
rabbitmqctl set_policy edam-consumer-timeout \
  '^q\.(video|document)\.preprocess$' \
  '{"consumer-timeout":14400000}' \
  --apply-to classic_queues
```

验证：

```bash
rabbitmqctl list_policies
# 应能看到 edam-consumer-timeout，apply-to=classic_queues
```

> 队列名以 `q.` 开头 —— 刻意收窄匹配范围，
> 免得把 broker 上别的应用的队列也一起改了。

### 3.3 RabbitMQ 4.3+ —— 只支持 quorum 队列

4.3 起，投递确认超时**只对 quorum 队列生效**，classic 队列不再支持。
EDAM 的队列目前是 classic（`declare_queue(durable=True)`，没声明队列类型）。

如果生产用的是 4.3+，两个选择：

1. **把队列改成 quorum 队列**（推荐）：
   ```python
   queue = await self.channel.declare_queue(
       queue_name,
       durable=True,
       arguments={"x-queue-type": "quorum"},   # ⚠️ 同样要删旧队列
   )
   ```
   然后用 3.2 的方式 B 挂 `consumer-timeout` 策略。

2. **接受 classic 队列没有投递超时**：那 worker 侧的
   `WORKER_TASK_DEADLINE_SEC` 就是唯一的闸门 ——
   它保证失败是可见的（`TaskDeadlineExceeded`），只是 broker 不再掐 channel。

---

## 四、验证配置生效

```bash
# 1. 节点级配置（3.x）
rabbitmqctl environment | grep -i consumer_timeout
# 应输出：consumer_timeout 14400000

# 2. 队列实际生效值（3.12+）
rabbitmqctl list_queues name arguments
# 或
rabbitmqctl list_queues name policy
```

**端到端验证**（最靠谱）：

```bash
# 上传一个大视频（转码要 > 30 分钟的那种），
# 然后盯着 worker 日志：
journalctl -u edam-worker -f | grep -E "hls_transcode_complete|deadline_exceeded|cancelled"
```

- 看到 `hls_transcode_complete` ⇒ 熬过了 30 分钟，配置生效 ✅
- 看到 `video_task_cancelled` ⇒ broker 还是在掐 channel ❌
- 看到 `video_task_deadline_exceeded` ⇒ worker 自己的上限太紧，调大
  `WORKER_TASK_DEADLINE_SEC`（但仍要小于 broker 的超时）

---

## 五、Worker 侧要一起改的

broker 的超时调大之后，worker 这两个值也要跟着对齐：

```bash
# /etc/edam/worker.env
WORKER_TASK_DEADLINE_SEC=10800      # 3 小时，必须 < broker 的 consumer_timeout
RABBITMQ_CONSUMER_TIMEOUT_SEC=14400 # 4 小时，必须 == broker 实际配置
```

> `RABBITMQ_CONSUMER_TIMEOUT_SEC` **不是** broker 的配置 ——
> 它是 worker 用来**自检**的"我以为 broker 是多少"。
> 写错不会影响 broker，但会让 worker 的启动自检失去意义
> （或者误报）。改了 broker 的配置，**必须**同步改这里。

K8s 环境（`helm/edam/values.yaml` 的 `worker.env`）同理。

---

## 六、关联文档

- `modify/2026-11-10-视频转码被broker掐断-consumer-timeout.md` —— 事故复盘与整改
- `worker/src/edam_worker/config.py` —— 三个超时的定义与排序说明
- `worker/src/edam_worker/main.py` —— 取消处理与超时闸门
- `monitoring/grafana/alerts/edam-rules.yaml` —— `WorkerTaskCancelledByBroker` 告警
