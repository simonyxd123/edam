# 死代码清理 + OpenAPI 契约与实现对齐（覆盖率 100%）

- 日期：2026-10-21
- 范围：worker/、backend/、doc/openapi.yaml、scripts/check_controller_coverage.py

---

## 一、worker 死代码清理

### 删除 `crypto/VideoEncryptor.py`（226 行）

零引用，且实现是**坏的**：

- `subprocess.run(cmd, check=True)` 被注释掉 → **FFmpeg 根本不执行**，
  随后的 `glob.glob("*.ts")` 必然为空
- 即便执行了，后处理用**同一个 IV** 加密整个 .ts，与 HLS 每分片独立 IV
  的语义不符 → 播放器解不开

HLS 加密已由 `processors/video.py` 的 ffmpeg 原生
（`-hls_key_info_file`）真正实现，这个旧实现只会误导人。

### `requirements.txt` 收敛到实际 import

删掉 **10 个从未被 import** 的包：

```
pika / sqlalchemy / aiomysql / pymysql / alembic / redis
python-multipart / tenacity / PyYAML / gmssl
```

- `gmssl` 是国密库，但后端的 SM2/SM3/SM4 是 **Java** 实现
  （`backend/.../crypto/gmsm/`），Python 侧并不调用国密 → 不需要
- `pika` 是同步版 MQ 客户端，项目只用异步的 `aio-pika`
- 顺带**补上一个漏声明的依赖**：`requests` 代码在用
  （`_notify_backend_status` / `ops.py`），但清单里没有 ——
  容器里只是碰巧装了

### 死配置项（4 个）

| 配置项 | 说明 |
|---|---|
| `HLS_KEY_URL` | 旧的 key URL 占位（默认 `https://api.example.com/...`，**从来没被真正使用过**）。HLS 密钥现在由 worker 自己随机生成、存 MinIO、由后端 `getKey` 下发 → 删掉并留注释说明去向 |
| `FINGERPRINT_HASH_SIZE` | 零引用 |
| `WATERMARK_STRENGTH` | 零引用（实际强度在 `DocWatermarkService.DEFAULT_STRENGTH` 里硬编码） |
| `LOG_LEVEL` | 零引用 → **接上** structlog 的过滤级别 |

### 未使用的 import（12 处）

`main.py` / `ops.py` / `fingerprint/*.py` 里的 `os`/`json`/`signal`/`sys`/
`io`/`tempfile`/`hashlib`/`np`/`datetime`/`Tuple`/`Optional`/`Any` 等。

### `main.py` 的 `handle_signal`（死代码）

原实现 `signal.signal(SIGTERM, ...)` + `sys.exit(0)` 有两个问题：

1. uvicorn 的 `capture_signals` 会在 `run()` 时接管 SIGINT/SIGTERM
   → **这个 handler 根本不会被触发**
2. 即使没被接管，`sys.exit(0)` 也会让 lifespan 的优雅关闭没机会执行
   → RabbitMQ 连接不关、正在转码的任务被硬切 → MinIO 上留半成品目录

改为不注册 handler（lifespan 里已 `await worker.stop()`）。

### `fingerprint/` 三个模块：标注"写好未接线"而非删除

它们是零引用，但属于**产品能力**（v3.3 W-6.1/6.2/6.3），不是垃圾代码。
已在各模块头部写清现状与接线前提：

- `PHashService` 的算法与 `processors/video.py` 用的 imagehash **不同**
  （手写 DCT + 中位数阈值 vs imagehash 的 DCT+缩放+高通滤波）
  → 两边指纹对不上，泄漏检测无法工作
- `LeakDetectionService` 依赖上面统一
- `DocWatermarkService` 与 `processors/watermark.py` 职责重叠

## 二、后端死代码清理

| 位置 | 问题 | 处理 |
|---|---|---|
| `JacksonConfig.DATE_TIME_FMT` | 定义了从未使用（且格式串 `.SS` 本身是错的） | 删 |
| `UserController.jwtTokenProvider` | 注入后从未使用 | 删 |
| `PlaybackController.keyUrl` | 赋值后从未使用（第 80 行又算了一遍） | 删 |

## 三、OpenAPI 契约与实现对齐：95% → 100%

### 现状

`scripts/check_controller_coverage.py` 报出 **20 个"实现但 OpenAPI 无"**的端点
（含这两轮新加的 `/auth/profile`、`/auth/change-password`、`/auth/sso/exchange`），
外加 openapi 里的 `/permissions`、`/roles` 与实际实现/前端用的
`/rbac/permissions`、`/rbac/roles` **对不上**。

契约缺端点 → `scripts/generate-sdks.sh` / `generate-postman.sh`
产出的客户端调不到这些接口。

### 修复

1. **openapi.yaml 补齐 14 个缺失端点**（auth 3 个 + rbac 8 个 + users 1 个 +
   videos 1 个 + playback 4 个 + documents 2 个 + dashboard 1 个 + v2 1 个）
2. **路径统一到 `/rbac/*`**：openapi 里的 `/permissions`、`/roles`、
   `/users/{id}/permissions` 改成 `/rbac/...`，与实现和前端一致
3. **删除 `PermissionsController`**（已标 @Deprecated、前端不用、纯重复）
   —— 唯一有价值的 `用户有效权限` 端点**迁到 `RbacController`**
   （路径 `/rbac/users/{id}/permissions`，正是 openapi 声明的那个）

### 顺带修 `check_controller_coverage.py` 的两个解析 bug

| bug | 后果 |
|---|---|
| 裸注解正则 `\([^)]*\)` 处理不了 `@PreAuthorize("hasAuthority('video:upload')")` 的**嵌套括号** | `POST /videos`、`/documents`、`/users` 三个端点全都没被识别 → 误报"未实现" |
| 不支持 `@GetMapping(value = "...", produces = "...")` 形式 | `playlist.m3u8` 与 `segment/{filename}` 两个端点漏检 |
| 两边路径写法天然不同（Spring 允许 `{filename:.+}` 正则后缀，OpenAPI 不允许） | `segment` 端点永远对不上 |

改为：**逐行扫描** mapping 注解（不再用整块正则），
并新增 `norm_path()` 统一去掉路径参数里的正则后缀。

**结果：82 个端点，100% 覆盖，0 缺失 0 多余。**

## 四、验证

```
后端 compile              OK
后端测试 (25)             OK
worker 语法               OK
DB 3 校验                 OK
OpenAPI 覆盖率            OK (100%)
前端 lint / vue-tsc       OK
前端 test (19)            OK
e2e (19)                  OK
helm × 3 overlay          OK
全部 YAML                 OK
```
