# Worker 端"按 video_id 重新触发 HLS"运维接口

- 日期：2026-10-20
- 影响：
  - worker/src/edam_worker/ops.py（新增）
  - worker/src/edam_worker/main.py（注入 publisher、挂载路由）
  - worker/src/edam_worker/config.py（OPS_ENABLED / OPS_TOKEN）

## 背景

上一轮 f55284a / e222b42 把"无法播放"的根因暴露给前端：
video_id=28 的 HLS 文件在 MinIO 里**不存在**，需要让 worker 重新
处理一遍才能复播。

线上常规做法是让 worker 重新消费 RabbitMQ 上 `q.video.preprocess`
队列。本仓库的队列 + payload 都已经由后端 VideoService 建立
（`backend/.../service/VideoService.java:196`），没必要再造一套。

## 接口设计

### 启用

环境变量：

```
OPS_ENABLED=true
OPS_TOKEN=<强随机 token>
```

- 任一缺失（含 OPS_ENABLED != true，OPS_TOKEN 为空）→ 接口对外
  **完全隐身（404）**。`secrets.compare_digest` 常量时间比较。
- token 不匹配 → 403

### 端点

所有端点都在 `/ops` 前缀下：

| Method | Path | 说明 |
|---|---|---|
| GET  | `/ops/health`                  | 不需要 token：回报 ops 是否启用 + token 是否配置 |
| GET  | `/ops/videos/{id}/hls-status`  | 探测 MinIO 上 `videos/{id}/hls/` 的真实文件清单 + fingerprint 存在性 |
| POST | `/ops/videos/{id}/reprocess-hls` | 入队重新触发；body: `{input_path, classification_lv?, employee_no?, dry_run?}` |

### 请求示例（reprocess-hls）

```bash
curl -X POST http://localhost:8001/ops/videos/28/reprocess-hls \
  -H "X-Ops-Token: $OPS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "input_path": "uploads/3/abc...mp4",
    "classification_lv": "L2",
    "employee_no": "SA0001",
    "dry_run": false
  }'
```

- 成功：202 Accepted，`{accepted:true, video_id, task_id, routing_key:"video.preprocess"}`
- 401/403：token 不对 / 未启用
- 503：worker 启动中 / RabbitMQ 不可达 / MQ publish 失败
- `dry_run:true`：不真正入队，返回 `would_publish_payload`，方便操作员先核对参数

### 设计要点

1. **不直接调后端查 video metadata**：worker 没有用户 JWT，
   `GET /videos/{id}` 要鉴权；让运维侧手动传 `input_path` 更直白，
   也不在 worker 里造第二套后端凭证管理
2. **复走 RabbitMQ + 现有 VideoProcessor**：避免另写一套 HLS 状态机。
   payload 字段对齐后端 publish 的格式（多塞一个 `ops_trigger:true`，
   用于日志追溯）
3. **passive=True 重复声明交换机**：复用已存在的 `edam.tasks` direct
   exchange，不引入新拓扑
4. **channel 不复用**：每个 publish 用独立的 channel，避免污染
   lifespan 时建立的"消费 channel"
5. **lifespan 启动成功后再注入 publisher**：`worker.start()` 抛错的话
   `/ops/*` 仍然可用，但只能 `503`，不会发冷消息到 MQ

## input_path 反查（操作员向）

不在本次代码改动范围内——操作员需要 `input_path` 时：

- 列 MinIO bucket：`mc ls edam/edam-videos/uploads/` 找原始上传
- 或者后端 `SELECT minio_path FROM video_resource WHERE id=28`
  （需要 dev 直连 MySQL 权限）

可以接受，因为触发频率极低（每次故障 / 重新上传前才用一次）。

## 不在本次范围

- 后端侧也加一个等价接口（带上 JWT 就能查 input_path 然后直接调
  worker）—— UI 上还是建议先走后端权限链路，不是这次的事
- 批量 reprocess / 异步进度回报（只支持单条同步返回，去重靠 RabbitMQ
  自然处理）

## 验证（在 worker 镜像里）

1. 默认状态（OPS_ENABLED 不设）：
   ```
   GET /ops/health → 200 {ops_enabled:false, token_configured:false}
   GET /ops/videos/28/hls-status → 404 ops endpoint disabled
   ```
2. 启 OPS_ENABLED=true、OPS_TOKEN=foo：
   ```
   GET /ops/health → 200 {ops_enabled:true, token_configured:true, ...}
   GET /ops/videos/28/hls-status (无 token) → 403 invalid ops token
   GET /ops/videos/28/hls-status + X-Ops-Token: foo
       → 200 {minio_reachable:true, m3u8_exists:false, segment_count:0, fingerprint_exists:false}
       （这就是出问题视频的实际诊断结果）
   POST /ops/videos/28/reprocess-hls + input_path="uploads/3/xxx.mp4"
       → 202 {accepted:true, routing_key:"video.preprocess", task_id:"..."}
       → 等数秒后 GET hls-status 复测，应看到 m3u8_exists:true

## 故障定位路径（与 f55284a / e222b42 联动）

前端症状"视频源不可达 (HTTP 404)" →
curl /ops/videos/{id}/hls-status 确认 m3u8 缺失 →
POST /ops/videos/{id}/reprocess-hls 重新入队 →
几分钟后刷新视频详情页可正常播放

按本项目 git-auto-commit 约定自动 commit + push。
