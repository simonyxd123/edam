# 审计日志全丢 + 回调被拒：两个静默失败

- 日期：2026-10-29
- 触发：后端在服务器上的运行日志里出现两类报错
  1. `AuditService - audit_log_write_failed … Data truncation: Invalid JSON text`
  2. `VideoController - video_status_update_unauthorized, video_id=2`

---

## 一、报错一：审计日志几乎全部写不进去

### 1.1 现象

```
ERROR c.example.edam.service.AuditService - audit_log_write_failed, user_id=3, op=login
org.springframework.dao.DataIntegrityViolationException:
### Cause: com.mysql.cj.jdbc.exceptions.MysqlDataTruncation:
  Data truncation: Invalid JSON text: "Invalid value." at position 0
  in value for column 'operation_log.detail'
```

登录、预览、登出……凡是带 `detail` 的审计记录**全部被 MySQL 拒绝**。

### 1.2 根因：列类型和实际用法对不上

建表时 `operation_log.detail` 定义成了 **JSON** 列：

```sql
-- V20260812_1000__init_schema.sql
detail JSON NULL,
```

但代码写进去的是**人读字符串**：

| 调用点 | 实际写入的内容 |
|---|---|
| `AuthController:74` | `"employee_no=" + 工号 + ", reason=" + 异常消息` |
| `AuthController:83` | `"employee_no=E000101"` |
| `AuthController:111` | `"refresh_token cleared"` |
| `DocumentController:187` | `"ip=127.0.0.1, mime=application/pdf, watermark=on"` |

没有一个是合法 JSON ⇒ MySQL 直接拒绝 ⇒ 记录丢失。

三处旁证说明 **JSON 这个类型从一开始就用错了**，不是后来改坏的：

- `AuditHelper.logAudit` 的 javadoc 写的是
  「`detail` 详细信息（**人读字符串**）」
- Java 实体里是 `private String detail;`
- 前端 `web/src/views/Audit.vue:15` 是 `detail?: string`，
  直接塞进表格列展示

### 1.3 为什么在合规系统里格外严重

写入失败只体现在服务端日志的一行 `ERROR` 里，没人会去看；
而审计表里还留着几条**没带 detail** 的记录（`detail` 为 NULL 时
JSON 列接受 NULL），看起来"表是好的，只是记录少" ——
更容易被当成"用户没做什么"，而不是"系统没记下来"。

按时间线看，这条链路从 2026-10-12 建表起就是断的 ——
也就是说，**系统上线至今的审计记录基本是空的**。

### 1.4 修法

新增迁移 `V20261029_1000__operation_log_detail_to_text.sql`：

```sql
ALTER TABLE operation_log
    MODIFY COLUMN detail TEXT NULL COMMENT '操作详情（人读字符串，非 JSON）';
```

**为什么改列，而不是改代码去写 JSON**：

1. 调用点遍布各 Controller，写的都是自由文本。强行 JSON 化要么逐个改成
   结构体（改动面巨大，还改变了调用方语义），要么统一包一层
   `{"raw": "..."}`（存进去的东西不再是人读的，前端还得跟着改）；
2. 审计日志的价值恰恰在于"人能看懂当时发生了什么"，JSON 化只增噪音；
3. 方案书对 `operation_log` 只列了表名与 ER 关系，没有规定 `detail` 类型。

另外在 `AuditService` 里加了**主动截断**（2000 字符）：
类型对了，但"内容太长被截断"仍会让整条审计记录写不进去 ——
审计丢失比截断严重得多，宁可少写几个字。

其余 JSON 列已排查，**没有问题**：
`webhook_delivery.payload`（写入的是序列化后的 JSON）、
`notification_preferences.channels/types`（无对应实体，未使用）、
`pwd_history`（无写入路径）、`dct_coefficients`（未接线）。

---

## 二、报错二：worker 的回调被后端拒了

### 2.1 现象

```
WARN c.example.edam.controller.VideoController - video_status_update_unauthorized, video_id=2
```

连续 3 次（10:52:13 / :14 / :16）。

**这正是上一轮记录里预言的"第二道坎"** —— worker 起来了、也真的处理了
视频（转码产物已在 MinIO 上），但回调被拒 ⇒ 后端的 `hls_status` 不会更新
⇒ 用户点播放还是 404。

### 2.2 根因：两边的 token 不一致

后端的回调端点是 **fail-closed** 的：

```java
// VideoController.isValidWorkerToken
if (expected == null || expected.isBlank() || provided == null) {
    return false;   // 后端没配 edam.worker.token ⇒ 一律拒绝
}
```

后端读 `edam.worker.token`（默认 `${WORKER_TOKEN:}`，**留空则一律拒绝**），
worker 发 `X-Worker-Token` 头。

K8s 侧两边都从同一个 secret 取 `worker-token`（`helm/edam/templates/
{deployment,worker-deployment}.yaml`），**裸机侧没人管** —— 后端是用
`mvn spring-boot:run` 手工起的，进程环境里没有 `WORKER_TOKEN` ⇒ 空 ⇒ 全拒。

### 2.3 原来的诊断有多糟

日志里只有一行 `video_status_update_unauthorized, video_id=2` ——
看起来像"回调参数不对"，完全看不出该改哪一边。真正的原因是**配置**，
而配置错误的表象在**另一个进程**里。

### 2.4 修法：让"该改哪一边"直接写在日志里

**后端**（`VideoController`）—— 三种拒绝原因分开报：

```java
log.warn("video_status_update_unauthorized, video_id={}, reason={}",
    videoId, tokenRejectReason(providedWorkerToken));
```

| reason | 含义 |
|---|---|
| `backend_token_not_configured` | 后端没配 `edam.worker.token` |
| `worker_token_header_missing` | worker 没发 `X-Worker-Token`（多半 worker 也没配） |
| `token_mismatch` | 两边都配了但值不一样 |

**不打印 token 本身**（连哈希都不打）—— 只报"哪一种不匹配"。

**worker** —— 启动时就说清楚配没配：

```
worker_token_not_configured  hint=后端要求 X-Worker-Token（fail-closed）…
```

**后端 `/health/ready`** —— 多报一段 `worker_callback`：

```json
"worker_callback": { "token_configured": false, "status": "misconfigured", "hint": "…" }
```

放在 ready 里是因为**运维脚本、巡检和人本来就在看这个端点**，
而"回调通道没通"这件事**不会**让后端自己变红 —— 它只在 worker 那边
表现为"转码完成了但状态没更新"，最后变成用户点播放还是 404。

刻意**不影响 HTTP 状态码**：后端本身完全能服务，只是回调进不来，
让整个后端 503 反而是过度反应。

**巡检脚本**（`ops/worker/edam-worker-healthcheck.sh`）——
读到 `misconfigured` 就告警，但**不重启 worker**：
重启解决不了配置问题，而且按本脚本的原则（不把依赖/配置故障算到
worker 头上），不该用重启来"处理"它。

### 2.5 顺带修的一个更隐蔽的问题：回调失败被当成成功

原来的 `_notify_backend_status` 只 `log.error` 不抛：

```python
if resp.status_code >= 400:
    log.error("notify_backend_failed", ...)   # ← 吞了
else:
    log.info("notify_backend_ok", ...)
```

后果：**任务被记成"成功"、消息被 ACK、Prometheus 显示 100% 成功率**，
而后端压根什么都没收到。这正是"静默失败"的典型形态 ——
监控全绿，功能是坏的。

现在按失败性质分开处理：

| 失败类型 | 处理 | 理由 |
|---|---|---|
| 401 / 403 | `PermanentWorkerError`，**不重试** | 配置问题，重转码 3 遍结果一样，只是白烧 CPU |
| 其它 4xx | `PermanentWorkerError`，不重试 | 请求本身有问题 |
| 5xx / 连不上 | 先在**回调层**便宜地重试 3 次 | 瞬时故障，不必重跑整个流水线 |
| 重试用尽 | 抛普通异常 → 外层重跑整个任务 | 转码贵，但"后端没收到状态"更贵 |

另外修了一个**会顶替原始异常**的坑：回调在 `finally` 里，
一旦它抛异常，**转码本身的原始异常就被吞了** ——
排障时看到的是回调错误，真正的错误看不见。现在分开处理：

- 转码已经失败 + 回调也失败 ⇒ 抛**转码**那个错（回调错误只记日志）
- 转码成功 + 回调失败 ⇒ 抛回调错误（任务记为失败，不记成功）

---

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `backend/.../db/migration/V20261029_1000__operation_log_detail_to_text.sql`（新） | `detail` 列 JSON → TEXT |
| `backend/.../service/AuditService.java` | detail 主动截断（2000 字符），避免超长又丢审计 |
| `backend/.../controller/VideoController.java` | 回调被拒时给出 `reason`（三选一），不泄露 token |
| `backend/.../controller/HealthController.java` | `/health/ready` 增加 `worker_callback` 段 |
| `backend/src/test/.../ApplicationStartupTest.java` | 真 MySQL 上验证审计能写人读字符串 detail |
| `backend/src/test/.../HealthControllerTest.java` | `worker_callback` 两种状态各一个用例 |
| `doc/openapi.yaml` | `HealthCheck` schema 补 `worker_callback` |
| `worker/src/edam_worker/errors.py`（新） | `PermanentWorkerError`：重试也没用的失败 |
| `worker/src/edam_worker/processors/video.py` | 回调失败按类型分流；不顶替原始异常；清理不跳过 |
| `worker/src/edam_worker/main.py` | `PermanentWorkerError` 记为 failed_permanent 不重试；启动时报告 token 状态 |
| `worker/src/edam_worker/config.py` | 新增回调重试配置 |
| `worker/tests/test_callback.py`（新） | 8 个用例覆盖回调的失败语义 |
| `ops/worker/edam-worker-healthcheck.sh` | 读到 `misconfigured` 告警（不重启） |
| `ops/worker/edam-worker.env.example` | 强调"两边都要配"；补回调重试配置 |
| `ops/worker/README.md` | 排障表补 4 行；配置表补 2 行 |

---

## 四、验证

| 项 | 结果 |
|---|---|
| worker 新用例（`tests/test_callback.py`） | 8 passed |
| 同上，对着**旧** `video.py` | 6 failed（证明用例真的锁住了这个行为） |
| worker 全量 | 25 passed, 2 skipped（本机无 PyPDF2 / ffmpeg） |
| pyflakes（src/tests/scripts） | 无 undefined name |
| 后端全量 | 67 个用例，6 个 error **全部是 `ApplicationStartupTest`**（本机无 MySQL，与未修改代码表现一致；CI 有真 MySQL 会跑） |
| `HealthControllerTest` | 10 passed（含新增 2 个） |
| `doc/openapi.yaml` | `yaml.safe_load` 通过 |

---

## 五、服务器上要做的（**关键**）

### 5.1 给后端配 WORKER_TOKEN（必须，否则视频还是播不了）

```bash
# 生成一个 token（两边用同一个）
TOKEN=$(openssl rand -hex 32)

# 后端：加到启动脚本/环境里，然后重启后端
export WORKER_TOKEN=$TOKEN
# 裸机通常是：nohup mvn spring-boot:run &  → 改成带环境变量启动

# worker：写进 /etc/edam/worker.env
echo "WORKER_TOKEN=$TOKEN" >> /etc/edam/worker.env
sudo systemctl restart edam-worker
```

验证：

```bash
curl -s http://127.0.0.1:8092/api/v1/health/ready | python3 -m json.tool | grep -A4 worker_callback
# 期望：token_configured: true, status: ok
```

### 5.2 拉新代码并重启后端（Flyway 会自动跑新迁移）

```bash
cd /var/data/project/edam && git pull
# 重启后端 —— 迁移 V20261029_1000 会自动执行（detail 改 TEXT）
```

验证审计写进去了：

```bash
mysql -uroot -p edam -e "select id,operation_type,detail from operation_log order by id desc limit 5;"
```

### 5.3 worker 用新版

```bash
cd /var/data/project/edam/worker/src
nohup python3 -m uvicorn edam_worker.main:app --port 8001 > /var/log/edam/worker.log 2>&1 &
```

### 5.4 顺带说明：服务器上 worker 之前为什么"跑了 3 次回调"

日志里 `video_id=2` 的回调被拒了 3 次 —— 那是**外层任务重试**的 3 次尝试
（`WORKER_MAX_ATTEMPTS=3`），每次尝试结束都会回调一次。
也就是说**转码本身也失败了**（否则只有 1 次回调）。
worker 自己的日志里有真正的失败原因：

```bash
grep -E "video_task_attempt_failed|video_processing_failed" /var/log/edam/worker.log | tail -20
```

改完 token 之后如果还失败，看这一行。

---

## 六、遗留

1. **审计表里丢失的历史记录补不回来** —— 那些行从来没进过库。
   需要的话只能从应用日志里捞（本次日志里能看到 `detail` 的原文），
   但建议不要补：审计记录的价值在于**当场记录、不可事后追认**。
2. **`operation_log` 按月分表**（实体注释里写着
   「对应表：operation_log（按月分表 operation_log_YYYYMM）」）——
   这个分表逻辑在代码里**没有实现**，目前都写进同一张表。
   量大了以后要单独做，本次未涉及。
3. **K8s 侧的 worker 镜像要重建**（同一个 Dockerfile CMD），
   迁移和 worker 改动都要进镜像。
