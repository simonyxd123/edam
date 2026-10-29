# Worker 启动即崩修复：worker 从来没起来过（不是"忘了起"）

- 日期：2026-10-29
- 触发：用户在服务器上手工起 worker 报错，
  `nohup python3 -m uvicorn edam_worker.main:app --reload --port 8001` →
  `NameError: name 'signal' is not defined`
- 性质：**P0 —— worker 进程自 2026-10-23 起就无法启动**，
  之前 `2026-10-28` 那次"播放 404"的根因判断（"Worker 进程没起来"）**不准确**，
  真实根因见下文。

---

## 一、真实根因：一次"死代码清理"把 worker 弄崩了

### 1.1 直接错误

`worker/src/edam_worker/main.py` 的**模块级**代码里有：

```python
def handle_signal(signum, frame):
    log.info("signal_received", signum=signum)
    asyncio.create_task(worker.stop())
    sys.exit(0)

signal.signal(signal.SIGTERM, handle_signal)   # ← signal 没 import
signal.signal(signal.SIGINT, handle_signal)    # ← sys 也没 import
```

这两个 import 是在 commit `ef09956`（*refactor: 死代码清理 + OpenAPI 契约与实现对齐*，
2026-10-23）里被删掉的：

```diff
-import os
-import signal
-import sys
```

而 `handle_signal` 函数和两行 `signal.signal(...)` 调用**留在原地没删**。

⇒ **模块导入即抛 `NameError`** ⇒ worker 在任何启动方式下都活不过第一秒：

| 启动方式 | 结果 |
|---|---|
| `nohup python3 -m uvicorn edam_worker.main:app` | ❌ NameError（用户遇到的就是这个） |
| `python3 -m edam_worker.main`（systemd unit 用的就是这个） | ❌ NameError |
| `docker/worker.Dockerfile` 的 `CMD` | ❌ NameError（容器 CrashLoopBackOff） |
| K8s `worker-deployment.yaml` | ❌ 同上（用的就是那个镜像） |

### 1.2 为什么之前没被发现

三件事叠加，把一个"启动即崩"变成了"静默失效"：

1. **`--reload` 掩盖了错误**：uvicorn 的 reloader 在子进程退出后会**自动再拉起它**
   （默认 0.25s 一次）。worker 一崩就被无限重启，日志里反复刷同一段 Traceback，
   看起来像"服务在跑但没干活"，而不是"根本没起来"。
2. **健康端点从来没被真正监听过**：`/health/live` 由 lifespan 启动，而 lifespan
   排在 import 之后 —— import 就崩了，端口压根没监听过。
3. **上一轮把症状当成了根因**：`2026-10-28` 记录里写的是
   "根因：Worker 进程没起来（手工 nohup 起的，没有进程守护）"，
   实际是"worker **起了就崩**"。方向错了 —— 所以那一轮补的 systemd 守护、
   队列卡死告警虽然本身有用，但**光有守护不够**：守护只会让一个崩掉的进程
   崩得更有节奏（systemd 会反复拉起它）。

### 1.3 讽刺的是，`ef09956` 的 commit message 写的就是要删它

> main.py 的 handle_signal：uvicorn 的 capture_signals 会接管 SIGTERM，
> 这个 handler 根本不会触发；且 sys.exit(0) 会让 lifespan 的优雅关闭
> 没机会执行 → RabbitMQ 连接不关、正在转码的任务被硬切 → MinIO 留半成品。
> **改为不注册（lifespan 里已 await worker.stop()）。**

**意图完全正确，执行漏了一半** —— 删了 import，忘了删调用。
于是"本来就不该触发的死代码"变成了"必然触发的启动阻塞"。

---

## 二、顺带发现的第二个缺陷：模块体被执行两次 ⇒ DuplicateTimeseries

即使把 `import signal` 加回去，`python -m edam_worker.main` **仍然起不来**：

```
python -m edam_worker.main
  └─ runpy 以 __main__ 的身份执行一遍模块体
       └─ __main__ 里的 uvicorn.run("edam_worker.main:app")
            └─ uvicorn 按名字 import edam_worker.main  ← 模块体第二次执行
                 └─ Counter("worker_tasks_processed_total", ...) 注册第二次
                      └─ ValueError: Duplicated timeseries in CollectorRegistry
```

关键点：**`python -m pkg.mod` 不会把 `pkg.mod` 放进 `sys.modules`**
（已实测验证：`exec as __main__ | pkg.mod in sys.modules: False`），
所以 uvicorn 的那次 import 是**真的一次新的模块执行**，不是缓存命中。

而指标是在**模块导入期**注册的（`prometheus_client` 的全局
`CollectorRegistry`），同名注册两次直接抛异常。

⚠️ 这条路径正是 **`ops/worker/edam-worker.service` 用的**
（`ExecStart=/usr/bin/python3 -m edam_worker.main`）——
上一轮刚提交的 systemd 守护，装上去也是起不来。

---

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `worker/src/edam_worker/main.py` | 删掉 `handle_signal` + 两行 `signal.signal(...)`（补完 `ef09956` 没做完的事）；`uvicorn.run("edam_worker.main:app")` → `uvicorn.run(app, ...)` |
| `worker/src/edam_worker/metrics.py`（新） | 三个 Prometheus 指标挪到这里 |
| `worker/tests/test_main_entrypoint.py`（新） | 6 个启动回归用例 |
| `.github/workflows/ci.yml` | worker-test job 增加"静态检查（未定义名字）"步骤 |
| `worker/requirements-dev.txt` | 加 `pyflakes==3.2.0` |
| `ops/worker/README.md` | 排障表补 3 行；说明 `--reload` 的崩溃循环陷阱 |

### 3.1 为什么指标要单独挪一个模块

指标注册**不幂等**：同名注册两次 ⇒ `Duplicated timeseries`。
放在 `main.py` 里意味着"模块体被执行几次就注册几次"。

挪到 `edam_worker/metrics.py` 之后，它是个**普通模块**，import 会被
`sys.modules` 缓存 ⇒ 无论 `main` 的模块体被执行几次，指标都只注册一次，
且拿到的是同一组对象。

这样即使将来有人又改回字符串形式 `uvicorn.run("edam_worker.main:app")`，
也不会再炸。**把"重复注册"从"启动即崩"降级成"无害"。**

### 3.2 为什么 `uvicorn.run(app)` 而不是字符串

字符串形式会让 uvicorn 再 import 一遍 `edam_worker.main`，而
`python -m` 已经以 `__main__` 的身份执行过一遍 ⇒ 模块体两次执行。
传 app 对象就没有这次多余的 import。

（两者都做，是有意的冗余：改法本身防一手重复执行，模块拆分再防一手。）

### 3.3 为什么不自己注册 SIGTERM handler

uvicorn 自己会接管 SIGTERM/SIGINT（`capture_signals`）：收到信号后
停止接受新连接 → 跑 lifespan 的关闭段 → 我们在 lifespan 里
`await worker.stop()` 关掉 RabbitMQ 连接。**这就是完整的优雅关闭。**

自己再注册一遍反而有害：

1. **覆盖** uvicorn 的 handler ⇒ lifespan 关闭段没机会执行
   ⇒ RabbitMQ 连接不关、正在转码的任务被硬切 ⇒ MinIO 留半成品目录；
2. 信号处理器里 `sys.exit(0)` 会在主线程抛 `SystemExit`，
   直接把事件循环掀掉，连"等当前任务收尾"都不会发生。

---

## 四、验证

### 4.1 回归测试（新加，6 个用例）

全部在**子进程**里跑 —— 既拿到干净的 import 环境，又能真实复现
"模块体被执行两次"这条链路。

| 用例 | 锁住的坑 |
|---|---|
| `test_module_import_succeeds` | `import edam_worker.main` 不抛异常（**ef09956 回归**） |
| `test_dunder_main_startup_succeeds` | `python -m edam_worker.main` 的模块体能跑通 |
| `test_module_body_executed_twice_does_not_crash` | 模块体执行两次不崩（**DuplicateTimeseries 回归**） |
| `test_metrics_module_is_idempotent` | 指标模块重复导入只注册一次 |
| `test_no_custom_signal_handler_installed` | import 阶段不改动 SIGTERM handler |
| `test_health_endpoints_registered` | 三个健康端点都在（守护脚本和 K8s 探针靠它们） |

**对着旧代码跑 → 4 failed + 1 error；对着新代码跑 → 6 passed**（已实测）。

### 4.2 手工验证

- `python -m uvicorn edam_worker.main:app` → 起来到 lifespan，
  打印 `worker_starting` / `metrics_server_started`，
  只在**连 RabbitMQ** 时失败（本机没有 RabbitMQ，属预期）
- `python -m edam_worker.main`（systemd 走的路径）→ 同上，
  **不再出现 DuplicateTimeseries**
- worker 全量测试：`17 passed, 2 skipped`（跳过的两个是本机没装 PyPDF2 / ffmpeg）

### 4.3 CI 加一道闸：这类 bug 不许再合进来

`ef09956` 那次改动**没有任何 CI 报警** —— 因为没人检查"删掉的 import
是不是真的没人用了"。这类 bug（删了 import 却留着调用 ⇒ import 即崩）
用 pyflakes **0.1 秒**就能查出来。

已在 `worker-test` job 里加了一步"静态检查（未定义名字）"：

```bash
python -m pyflakes src/ tests/ scripts/ > /tmp/pyflakes.txt 2>&1 || true
if grep -q "undefined name" /tmp/pyflakes.txt; then ... exit 1; fi
```

只卡 `undefined name` 这一类**致命**问题，不卡"未使用的 import"之类风格问题
（`tests/`、`scripts/` 里有一批历史遗留的风格告警，不在本次范围内清理）。

实测：**对着旧代码 → 拦下；对着新代码 → 通过。**

---

## 五、对上一轮结论的订正

`modify/2026-10-28-fix-playback-404-when-hls-not-ready.md` 里写的

> 根因：Worker 进程没起来（手工 nohup 起的，没有进程守护）

**订正为**：根因是 **worker 启动即崩**（`NameError`），
自 2026-10-23 起就从未真正运行过。因此：

- 上一轮补的 **systemd 守护** 仍然必要（防进程死掉/机器重启），
  但它**不能**替代修好启动本身 —— 守护一个崩掉的进程只会让它崩得更有节奏；
- 上一轮补的 **队列卡死告警** 反而是这次能定位到的关键：
  `edam_transcode_queue_depth > 0` 持续 30 分钟 → 告警，
  说明"有人往队列里投东西，但没人消费"。

---

## 六、遗留 / 待办

1. **服务器上重新拉代码并重启 worker**（必须，否则这次修复不生效）：
   ```bash
   cd /var/data/project/edam && git pull
   cd worker/src
   nohup python3 -m uvicorn edam_worker.main:app --port 8001 > /var/log/edam/worker.log 2>&1 &
   curl -s http://127.0.0.1:8001/health/worker | python3 -m json.tool
   ```
   > **不要带 `--reload`** —— 它会把崩溃变成无限重启循环，掩盖真实错误。
   > 长期运行请用上一轮的 systemd：`sudo ./ops/worker/install.sh`
2. **卡在队列里的那条任务会自动被消费** —— worker 起来后
   `q.video.preprocess` 里那条消息会被取走，HLS 会生成，视频能播。
3. **服务器是 Python 3.9，方案书要求 3.10+** —— 当前代码兼容 3.9
   （已确认无 3.10+ 语法），但不建议长期停在 3.9（3.9 已 EOL，2025-10 起不再有安全更新）。
   升级需要重装依赖（numpy 1.26.4 / opencv 4.10 在 3.10 上都有 wheel）。
4. **K8s 侧的 worker 镜像同样崩过**（同一个 Dockerfile CMD）——
   重新构建镜像 `edam/worker:latest` 后 rollout 即可。
