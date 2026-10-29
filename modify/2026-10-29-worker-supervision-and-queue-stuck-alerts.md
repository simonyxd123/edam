# Worker 守护 + 队列卡死告警：让"转码没人消费"不再靠用户点播放才发现

- 日期：2026-10-29
- 触发：上一轮修完"播放 404"（`2026-10-28-fix-playback-404-when-hls-not-ready.md`）后，
  处理那篇记录里写明的**遗留项** —— Worker 没有进程守护、且没有任何告警能提前发现它挂了。

---

## 一、上一轮留下的洞

`2026-10-28` 的结论是：用户看到的 404，根因是 **Worker 进程没起来**，
转码任务卡在 RabbitMQ 队列里没人消费（`queue_depth = 1`）。

那次只修了**症状**（404 措辞、前端轮询），**根因的两条防线一条都没补**：

| 防线 | 当时状态 | 后果 |
|---|---|---|
| 进程守护 | 裸机上手工 `nohup` 起的，**没有 systemd / supervisor** | 机器重启或进程被 OOM kill ⇒ 队列永远卡住 |
| 提前告警 | 队列深度只在 `/health/ready` 的 **JSON** 里 | Prometheus 抓不到 JSON 字段 ⇒ **配不出告警** |
| 已有积压告警阈值 | `rabbitmq_queue_messages_ready > 1000` | 现场只有 **1 条**，阈值等于永远不触发 |

也就是说：**同样的故障还会再发生一次，而且依然要等用户点播放才会被发现。**

K8s 侧其实有 `worker-deployment.yaml`（Deployment + liveness/readiness 探针），
但**裸机侧什么都没有** —— 而 2026-10-28 出事的那台服务器正是裸机。

---

## 二、本次补的三道防线

### 防线 1：进程守护（`ops/worker/`）

| 文件 | 作用 |
|---|---|
| `edam-worker.service` | systemd unit：`Restart=always` + `RestartSec=5`，进程挂了 5 秒后自动拉起；`TimeoutStopSec=30` 优雅停机；10 分钟内重启 >5 次就停下等人处理 |
| `edam-worker.env.example` | 环境变量模板（含 `WORKER_TOKEN` 等关键项的说明），真实文件放 `/etc/edam/worker.env`（600 权限，**不入库**） |
| `edam-worker-healthcheck.sh` | 每分钟巡检 + 自愈（见下） |
| `edam-worker-healthcheck.service` / `.timer` | 巡检的 systemd 定时器（`OnUnitActiveSec=1min`） |
| `install.sh` | 幂等安装脚本 |
| `README.md` | 配置说明、日常运维、**排障对照表** |

### 防线 2：队列深度变成真正的 Prometheus 指标

**后端**新增 `com.example.edam.metrics.TranscodeQueueMetrics`：

- `edam_transcode_queue_depth{queue="..."}` —— 三个业务队列
  （`q.video.preprocess` / `q.document.preprocess` / `q.watermark`）的 ready 消息数
- `edam_transcode_queue_reachable` —— 1 = 查得到，0 = RabbitMQ 不可达

两条刻意的设计：

- **定时刷新（30s），不在 scrape 时现查。**
  `rabbitAdmin.getQueueInfo()` 在 RabbitMQ 抖动时会阻塞几秒；
  把它甩进 scrape 线程会让整个 actuator 端点超时，**连带其它指标一起丢**。
- **取不到时保留上一次的值，绝不置 0。**
  置 0 等于告诉告警"队列已清空"，把真实故障掩盖掉；
  保留旧值 + `reachable=0`，才分得清"积压"和"查不到"两种故障。

### 防线 3：告警规则（`monitoring/grafana/alerts/edam-rules.yaml`）

新增 `edam-worker` 组：

| 告警 | 条件 | 级别 | 对应故障 |
|---|---|---|---|
| `WorkerDown` | `min(up{job=~".*worker.*"}) == 0` 或目标缺失，持续 2m | critical | **本次故障的直接原因**（worker 没跑） |
| `VideoTranscodeQueueBacklog` | 队列深度 > 0 持续 10m | warning | 转码流水线停滞，用户点播放会 404 |
| `VideoTranscodeQueueStuck` | 队列深度 > 0 持续 30m | critical | 同上，更久未自愈 |
| `TranscodeQueueBacklog` | 文档/水印队列深度 > 0 持续 15m | warning | 另一条链路 |
| `QueueDepthProbeFailing` | `reachable == 0` 持续 5m | warning | RabbitMQ 不可达 —— 此时积压告警失效，需单独报 |

用 `min(up{...})` 而不是 `max()`：多副本时**任意一个副本**挂掉都要报，
`max()` 会把"一半副本死了"藏起来。

已有的 `RabbitMQQueueDepth`（阈值 1000）**保留**，但加了注释指向新规则 ——
它依赖 rabbitmq_exporter，新规则不依赖，而且后端自己能报深度。

### 防线 4（补）：裸机没有 Prometheus 时的自愈

`edam-worker-healthcheck.sh` 每分钟做两件事：

1. **`/health/live` 不响应** ⇒ 重启 worker（进程卡死 / 假死）
2. **进程活着、但后端报 `queue_depth > 0` 连续 3 次** ⇒ 判定"不消费"，重启 worker

判定原则与后端 `HealthController` 的 liveness 一致：**只看 worker 自己，
不把依赖故障算到 worker 头上** —— RabbitMQ 挂了的时候重启 worker 没用，
只会掩盖真正的 RabbitMQ 故障。所以脚本**不判** `/health/worker` 的 `overall_ok`，
只判"进程是否响应"；后端健康端点取不到时直接跳过、不计数、不重启。

---

## 三、Helm：worker 指标此前根本没被抓（死规则）

`edam-rules.yaml` 里早就有一条 `VideoTranscodeFailure`，
用的是 `worker_tasks_processed_total` —— 但这个指标**从来没被 Prometheus 抓到过**：

- worker 内部 `start_http_server(METRICS_PORT=9100)` 起了一个独立的指标端口
- helm 的 `worker-deployment.yaml` 只暴露了 8001（http），**没有 9100**
- chart 里**只有 backend 的 ServiceMonitor**，没有 worker 的

⇒ 那条告警是一条**永远不触发的死规则**。本次补齐：

| 文件 | 改动 |
|---|---|
| `helm/edam/templates/worker-service.yaml`（新） | worker 的 Service，暴露 `http`(8001) + `metrics`(9100) 两个端口 |
| `helm/edam/templates/servicemonitor-worker.yaml`（新） | worker 的 ServiceMonitor，抓 `/metrics` |
| `helm/edam/templates/worker-deployment.yaml` | 增加 `metrics` 容器端口（9100） |
| `helm/edam/values.yaml` | 新增 `worker.metricsPort`(9100)、`worker.serviceMonitor`；`worker.env.METRICS_PORT=9100` |
| `helm/edam/README.md` | 说明两个 ServiceMonitor + 端口必须一致的坑 |

⚠️ `worker.metricsPort` 与 `worker.env.METRICS_PORT` **必须一致**，
不一致时 `/metrics` 抓不到，`VideoTranscodeFailure` 又是死规则 —— README 里写明了。

---

## 四、验证

- 后端：`TranscodeQueueMetricsTest` 3 个用例全绿（指标注册 / 深度刷新 / 不可达保留旧值）
- 后端全量：64 个用例，只有 `ApplicationStartupTest` 的 5 个红（本机无 MySQL，
  与未修改的原始代码表现一致）
- `monitoring/grafana/alerts/edam-rules.yaml`：`yaml.safe_load` 通过，
  组内无重复告警名（`AuditWriteFailure` 曾经因为插入位置选错而重复，已修）
- `ops/worker/*`：`bash -n` 语法检查通过；unit 文件 configparser 解析出正确的段
- helm：模板引用的 `.Values.worker.*` 全部在 `values.yaml` 中存在（无 helm 二进制，改用脚本核对）

---

## 五、遗留 / 待办

1. **服务器上还没装这套 systemd 守护** —— 需要在服务器上执行：
   ```bash
   cd /var/data/project/edam
   sudo ./ops/worker/install.sh
   sudo vi /etc/edam/worker.env      # WORKER_TOKEN 等
   sudo systemctl restart edam-worker
   curl -s http://127.0.0.1:8001/health/worker | python3 -m json.tool
   ```
2. **`dev/docker-compose.yml` 里仍然没有 worker 服务** —— 本地开发栈起 worker 只能手工起。
   要不要把 worker 也做成 compose 服务（需要 python + ffmpeg 镜像）待定：
   好处是"起栈即有 worker"，坏处是 GPU/FFmpeg 依赖会让 compose 变重。
3. **Prometheus Operator 未确认装了 rabbitmq_exporter** —— 若没装，
   `rabbitmq_up` / `RabbitMQDown` / `RabbitMQQueueDepth` 这几条规则同样不会触发；
   新的队列深度规则不依赖它，所以不受影响。
