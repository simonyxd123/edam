# EDAM Worker 运维手册（裸机 / 非 K8s）

> 这份手册针对 **裸机部署**（`nohup python3 -m uvicorn ...` 这种）。
> K8s 环境用 `helm/edam/templates/worker-deployment.yaml`，有 Deployment + 探针 + HPA 自带守护。

---

## 一、为什么有这份手册

2026-10-28 出过一次**用户可见故障**：

```
网络异常，正在重试（1/3）… (HTTP 404)
```

排查结论（详见 `modify/2026-10-28-fix-playback-404-when-hls-not-ready.md`）：

| 检查项 | 结果 |
|---|---|
| `GET /api/v1/videos/1` | `hls_status=pending`、`hls_path=null` |
| MinIO `videos/1/` 前缀 | **0 个对象** —— 视频从没被转码过 |
| `/api/v1/health/ready` | `rabbitmq.queue_depth = 1` |

**根因：Worker 进程没起来**（手工 `nohup` 起的，没有进程守护），
转码任务躺在 RabbitMQ 队列里没人消费。

`queue_depth = 1` 是这次故障的**唯一信号**，但当时没有任何告警能提前发现：

- 队列深度只在 `/health/ready` 的 **JSON** 里 → Prometheus 抓不到，配不出告警
- 已有的 `RabbitMQQueueDepth` 阈值是 **1000 条** → 现场只有 1 条，永远不触发

本次补的三道防线：

1. **进程守护**（本目录的 systemd unit）—— 进程挂了自动拉起
2. **队列深度变成 Prometheus 指标**（后端 `edam_transcode_queue_depth`）
   + 告警 `VideoTranscodeQueueBacklog` / `VideoTranscodeQueueStuck`
3. **每分钟巡检 + 自愈**（`edam-worker-healthcheck.timer`）——
   进程活着但不消费队列时自动重启

---

## 二、安装

```bash
cd /var/data/project/edam
sudo ./ops/worker/install.sh
```

脚本做四件事（幂等，可重复跑）：

1. 安装 unit 到 `/etc/systemd/system/`
2. 装健康巡检脚本到 `/usr/local/bin/`
3. 生成 `/etc/edam/worker.env`（**已存在则不覆盖**）
4. `enable --now` worker + 巡检定时器

装完**必须**改配置并重启：

```bash
sudo vi /etc/edam/worker.env          # WORKER_TOKEN / 密码 / 地址
sudo systemctl restart edam-worker
```

> ⚠️ `WORKER_TOKEN` 必须与后端的 `edam.worker.token` **完全一致**。
> 不一致/为空的表现是：转码做完了，但后端的 `hls_status` 一直不变 →
> 用户点播放还是 404，而且日志里看不出任何异常。

---

## 三、配置说明（`/etc/edam/worker.env`）

模板见 `edam-worker.env.example`。所有变量含义见 `worker/src/edam_worker/config.py`。

| 变量 | 默认 | 说明 |
|---|---|---|
| `WORKER_TOKEN` | **空** | Worker → 后端回调的共享密钥。**为空则后端一律拒绝回调** |
| `BACKEND_BASE_URL` | `http://localhost:8092` | 回调地址，带 context-path：`http://127.0.0.1:8092/api/v1` |
| `RABBITMQ_HOST/PORT/USER/PASSWORD` | localhost/5672/edam/edampass | 队列连接 |
| `MINIO_ENDPOINT` | `http://localhost:9000` | 读写切片、密钥、水印 |
| `MINIO_ROOT_USER/PASSWORD` | minioadmin | 对应桶权限 |
| `HTTP_PORT` | 8001 | 健康探针端口 |
| `METRICS_PORT` | 9100 | Prometheus 指标端口 |
| `ENABLE_METRICS` | true | 关掉就没有指标，告警随之失效 |
| `FFMPEG_PATH` / `FFPROBE_PATH` | `/usr/bin/ffmpeg` | 装到别处必须改 |
| `OPS_ENABLED` | false | 运维接口（按 video_id 重触发 HLS），默认关 |
| `WORKER_MAX_ATTEMPTS` | 3 | 整个流水线重试次数（下载+转码+回调，很贵） |
| `WORKER_CALLBACK_MAX_ATTEMPTS` | 3 | 回调后端失败时的重试次数（只重发 PATCH，很便宜）；401/403 不重试 |

---

## 四、日常运维

```bash
# 状态
systemctl status edam-worker
journalctl -u edam-worker -f              # 实时日志
journalctl -u edam-worker -n 200 --no-pager

# 自检（RabbitMQ / MinIO / 后端 + 最近一次任务）
curl -s http://127.0.0.1:8001/health/worker | python3 -m json.tool

# 巡检定时器
systemctl list-timers edam-worker-healthcheck.timer
systemctl start edam-worker-healthcheck.service   # 手动跑一次

# 重启 / 停 / 开机自启
sudo systemctl restart edam-worker
sudo systemctl stop edam-worker
sudo systemctl enable --now edam-worker
```

**为什么 worker 不用 `--reload`**：`--reload` 是开发用的，进程会被子进程接管、
systemd 认不出主进程，stop/restart 会失灵。生产一律 `python3 -m edam_worker.main`。

> ⚠️ **`--reload` 还有一个更阴的副作用**：uvicorn 的 reloader 在子进程退出后会
> **自动把它再拉起来**（默认 0.25s 一次）。worker 启动即崩时，它就变成
> "无限重启循环" —— 日志里反复刷同一段 Traceback，真正的错误被淹没在循环里，
> 看起来像"服务在跑但没干活"。2026-10-29 那次就是这么被误导的：
> 真实错误是 `NameError: name 'signal' is not defined`。
>
> 排查启动问题时请**去掉 `--reload`** 跑一次，让错误一次性暴露出来。

---

## 五、排障：视频还是 404 / 转码不动

按这个顺序查：

```bash
# 1) worker 进程活着吗？
systemctl is-active edam-worker
curl -s http://127.0.0.1:8001/health/worker | python3 -m json.tool

# 2) 队列里有积压吗？（后端能直连 RabbitMQ）
curl -s http://127.0.0.1:8092/api/v1/health/ready | python3 -m json.tool | grep -A3 queue_depth

# 3) 积压但 worker 活着 ⇒ 消费卡死，重启它
sudo systemctl restart edam-worker

# 4) worker 处理完有没有成功回调后端？
journalctl -u edam-worker -n 200 --no-pager | grep -i "callback\|status\|token"
```

排查对照表：

| 现象 | 原因 | 处理 |
|---|---|---|
| `systemctl is-active` = inactive | 进程挂了 | `Restart=always` 应自动拉起；查 `journalctl` 找崩溃原因 |
| **启动即崩**，日志里有 Traceback | 代码版本旧于 2026-10-29 的启动修复 | `git pull` 后重启；见 `modify/2026-10-29-fix-worker-startup-crash.md` |
| 日志反复刷同一段 Traceback，进程却"在重启" | 用了 `--reload`，reloader 把崩溃变成无限重启循环 | 去掉 `--reload` 跑一次，让错误一次性暴露 |
| worker 活着，`queue_depth > 0` 不降 | 不消费 / 回调失败 | `journalctl` 里有无 task 报错；`WORKER_TOKEN` 是否与后端一致 |
| `queue_depth > 0`，日志里全是连接错误 | RabbitMQ/MinIO 不可达 | 修依赖；**不要**怪 worker |
| `hls_status` 一直 pending，但队列已空 | 回调被后端拒了 | `WORKER_TOKEN` 与后端 `edam.worker.token` 不一致 |
| 后端日志 `video_status_update_unauthorized ... reason=token_mismatch` | 两边 token 值不同 | 两边改成同一个值 |
| 后端日志 `... reason=backend_token_not_configured` | 后端没配 | 给后端进程加 `WORKER_TOKEN=<值>` 并重启后端 |
| 后端日志 `... reason=worker_token_header_missing` | worker 没配 | 给 worker 配 `WORKER_TOKEN` 并重启 worker |
| MinIO 有对象但播放仍 404 | 产物不在 `videos/{id}/hls/playlist.m3u8` | 核对对象路径；见 `modify/2026-10-28-...md` |
| 审计页面查不到登录/预览记录 | `operation_log.detail` 列类型不对，写入全被 MySQL 拒 | 见 `modify/2026-10-29-fix-audit-log-json-and-worker-callback.md` |

---

## 六、K8s 环境对照

| 能力 | 裸机（本目录） | K8s（helm chart） |
|---|---|---|
| 进程守护 | systemd `Restart=always` | Deployment 自动拉起 |
| 存活探针 | 每分钟巡检脚本 | `livenessProbe: /health/live` |
| 就绪探针 | `/health/worker` 手动看 | `readinessProbe: /health/ready` |
| 指标采集 | 需自行接 Prometheus | `servicemonitor-worker.yaml` |
| 积压告警 | 巡检脚本自愈 | `VideoTranscodeQueueBacklog` / `Stuck` |

K8s 里 worker 的 `METRICS_PORT` 必须与 `worker.metricsPort`（默认 9100）一致，
否则 `/metrics` 抓不到，`worker_tasks_processed_total` 消失，
`VideoTranscodeFailure` 告警就成了永远不触发的死规则。
