# 全项目审查与 P0 修复（第一轮）

- 日期：2026-10-20
- 范围：backend（110 文件 / 10K 行）、worker（12 文件 / 2.2K 行）、
  web/src（26 文件 / 4.3K 行）、helm / gitops / docker / CI
- 方法：4 个并行审查 agent（后端 / worker / 前端 / 部署配置）+
  人工交叉复核关键结论

本轮聚焦 **P0（会出事）**，把"看起来有、实际上没有"的安全控制和
"声明了 A 但实现是 B"的不一致收口。

---

## 一、HLS AES-128 加密：整条链路此前是断的（最高优先级）

### 现状（修复前）

| 环节 | 实际行为 |
|---|---|
| worker FFmpeg 命令 | **没有任何加密参数** → 产出明文分片 |
| worker `_rewrite_key_url` | 正则 `URI="[^"]*"` 在无 EXT-X-KEY 的 m3u8 里零匹配 → **纯 no-op**，却照样打 "改写成功" 日志 |
| `settings.HLS_KEY_URL` 默认值 | `https://api.example.com/...` —— 不存在的占位域名 |
| 后端 `getKey` | 返回**硬编码** key `[1..16]`，且 `@RequestHeader("X-Session-Token")` 是必填 —— hls.js 拉 key 不带自定义头 → Spring 直接 400 |
| `SecurityConfig` | `/playback/*/key` **未** permitAll → 401 |
| `VideoEncryptor.encrypt_hls_with_ffmpeg` | 完整实现了加密，但**全项目无人调用**（死代码），且其内部 `subprocess.run(cmd)` 也是注释掉的 |
| 文档承诺 | ARCHITECTURE.md:148 / README.md:251 都写着 "AES-128 HLS 切片" |

净效果：**"看起来加密了，其实全明文"**。对一个防泄密产品，
安全评审会签字放行，但实际没有保护。

### 修复

**worker**（`processors/video.py`）
- 生成 16 字节随机 key，写 `key.info`（`URI key.bin` + hex key，不写 IV 让
  FFmpeg 为每分片生成独立 IV），FFmpeg 加 `-hls_key_info_file`
- `_assert_encrypted()`：转码后**校验 m3u8 里确有 `#EXT-X-KEY`**，
  没有就抛错拒绝上传 —— 杜绝"静默产出明文"
- key 存两处：`videos/{id}/hls/key.bin`（随 HLS 目录上传）+
  `videos/{id}/key.bin`（**独立路径**，刻意放在 hls/ 之外，
  避免被 segment 代理暴露）
- `_upload_hls` 跳过 `key.info`（FFmpeg 中间产物不该进对象存储）

**后端**（`PlaybackController`）
- `getPlaylist` 改写 m3u8 时，**同时**把 `URI="key.bin"` 改成
  `{base}/api/v1/playback/{id}/key?token=...`（与 segment 改写同一套机制）
- `getKey` 改为：从 MinIO 读 `videos/{id}/key.bin`，**query token 鉴权**
  （不用请求头 —— hls.js 发不出自定义头），长度必须为 16
- `SecurityConfig` 放行 `GET /playback/*/key`

---

## 二、身份来源：客户端可伪造的 `X-User-Id` 头（系统性）

### 现状

**41 处** controller 用 `@RequestHeader("X-User-Id")` 取当前用户。
那个头是**客户端随便填的** —— 改成 `1` 就能以 admin 身份
上传/删除/重置他人资源，并伪造审计日志。

真正的身份早就在 `JwtAuthenticationFilter` 里解析进 SecurityContext
了（principal = userId），只是 controller 没用。

### 修复

新增自定义参数解析器，**controller 方法体一行不用改**：

- `security/CurrentUserId.java` —— 注解
- `security/CurrentUserArgumentResolver.java` —— 解析器
- `security/CurrentUser.java` —— `requireUserId()` / `optionalUserId()`
  / `hasAuthority()` / `isAdmin()`
- `config/CurrentUserWebMvcConfig.java` —— 注册解析器

41 处 `@RequestHeader("X-User-Id")` 全部机械替换为 `@CurrentUserId`
（两处 `required=false` → `@CurrentUserId(required = false)`）。

---

## 三、认证相关的假验证（全部改为 fail-closed）

| 问题 | 原行为 | 修复 |
|---|---|---|
| **MFA** | 只校验"是不是 6 位数字"，**从不比对** `mfaSecret`（该字段全项目从没被写入也从没被读取） | 新增 `security/TotpUtil.java` 实现 RFC 6238 TOTP（HMAC-SHA1 / 6 位 / 30s / ±1 窗口），真正比对密钥 |
| **账号锁定** | `handleFailedLogin` 的 `updateById` 与后续 `throw` 在同一 `@Transactional` 里 → **失败计数被事务回滚丢弃**，连续 5 次锁定一次都不会触发 | 抽出 `security/LoginAttemptService`，用 `REQUIRES_NEW` 独立事务落库 |
| **SAML2 SSO** | `resolveUserInfo` **完全不用** SAMLResponse，直接返回硬编码身份 `saml2_<id>_user` / `SSO_USER` → 任意回调都能拿到合法 JWT | 改为抛 `UnsupportedOperationException`，拒绝登录（接入 `spring-security-saml2-service-provider` 前不许开） |
| **WebAuthn** | `verifyAssertionSignature` 只做 base64 解码然后打 "skipped" 日志；注册侧把 attestation 的 SHA-256 当公钥 | 改为无条件抛异常，fail-closed（需接入 webauthn4j） |
| **默认 admin** | 硬编码 `admin123`，**无 profile 开关**，production 也会建 | 改为 `edam.bootstrap.admin-password` 配置项，为空则**不创建任何默认账号**；`must_change_password` 也不再是摆设（置位但从未被读的问题记录在待办） |

---

## 四、越权与权限缺失

| 位置 | 问题 | 修复 |
|---|---|---|
| `DocumentController.batchDelete` | **没有** `@PreAuthorize`（同源的 `VideoController.batchDelete` 有）→ 任何登录用户可批量删任意文档 | 加 `hasAuthority('document:delete')` |
| `DistributionController.decide` / `revoke` | **没有** `@PreAuthorize` → 任何登录用户可审批/撤销任意外发申请；且 `request.getDecision().equals("approve")` 在 null 时 NPE | 加 `hasAuthority('distribution:approve')`；decision 显式校验取值范围 |
| `VideoService.delete` / `DocumentService.delete` | 归属校验只 `log.warn` 就照样删 → IDOR | 非 owner 且非 admin → `AccessDeniedException` |
| `PATCH /videos/{id}/status` | **permitAll** → 任何人可把没转码的视频标成 ready，或把 `hls_path` 指向任意 MinIO 对象（配合同样 permitAll 的 playlist.m3u8 构成"任意对象读"链） | 要求 `X-Worker-Token` 头（常量时间比较；未配置则一律拒绝） |

---

## 五、Worker：自愈能力此前被架空

### 现状

| 问题 | 后果 |
|---|---|
| `main.py` 用了 `aio_pika.DeliveryMode` 但**没 `import aio_pika`** | 昨天刚加的 `POST /ops/videos/{id}/reprocess-hls` **100% NameError**，唯一的自愈手段是死的 |
| `VideoProcessor.process` 的 `except` 块**没有 raise** | 昨天加的 `WORKER_MAX_ATTEMPTS=3` 重试**一次都不会触发**；消息被直接 ACK 丢弃，DB 永久停在 failed，Prometheus 还显示 100% 成功 |
| `WatermarkProcessor` **没有 `process()` 方法** | `handle_watermark` 取 `self.watermark_processor.process` 作实参时抛 AttributeError（发生在 try 之外）→ 每条 watermark 消息都打崩消费者回调，成为毒丸 |
| `hls_status = 2` 在 `_upload_hls` **之前**置位 | 上传到一半失败 → MinIO 上只有半套分片，后端却收到 "ready" → 前端提示"处理完成"，点进去 404 |
| HLS 目录 + 指纹 JSON 落在 `/tmp` 且**从不清除** | 长驻 worker 每处理一个视频就在容器可写层留下完整分片集，几百个视频后磁盘写满 |
| `document.py` 的 `finally` 引用可能未定义的 `local_path` | NameError 顶替掉真正的原始异常，重试 3 次都是这个假错误 |
| `document.py` 的 `.docx.wm` 扩展名 + LibreOffice **不检查 returncode** | docx 预览**从来没生成过**，且失败被静默吞掉 |
| `_extract_fingerprints` 在事件循环里同步逐帧解码 | 1 小时 1080p 视频会把整个 worker 卡死（prefetch=10 的其余任务、/health 全部无响应） |
| `except BaseException` 吞掉 `asyncio.CancelledError` | SIGTERM 关闭流程被反复拖延，且留下半成品目录 |

### 修复

- 补 `import aio_pika` 语义（改用 `from aio_pika import DeliveryMode`）
- `VideoProcessor.process` 的 except 块 **raise**（状态回调由 finally 负责，与重抛不冲突）
- 新增 `WatermarkProcessor.process()`（下载 → 嵌入 → 上传 watermarks bucket → 清理）
- `hls_status` / `fp_status` 移到**上传成功之后**才置 ready
- 统一工作目录 `tempfile.mkdtemp()`，`finally` 里 `shutil.rmtree` 整目录清理
- `document.py`：`local_path = None` 前置；`os.path.splitext` 换扩展名；
  LibreOffice 检查 returncode + 独立 `-env:UserInstallation`；`mktemp` → `mkdtemp`
- 指纹提取整体包进 `asyncio.to_thread`；`cap.release()` 放 finally
- `except BaseException` → `except Exception` + `CancelledError` 单独 re-raise

---

## 六、其他后端修复

| 位置 | 问题 | 修复 |
|---|---|---|
| `VideoService.upload` | 扩展名用 `contentType.substring(indexOf('/')+1)` → `Content-Type: video/../../../../secret` 可**目录穿越** MinIO object name | 新增 `detectVideoExtension()`：**白名单** mime 子类型 + 原始文件名兜底 |
| `PlaybackController` 两处 | MinIO `InputStream` 不是 try-with-resources → `readAllBytes()` 抛异常时连接泄漏 | 改 try-with-resources |
| `GlobalExceptionHandler` | 缺 `ResponseStatusException` handler → `ExceptionHandlerMethodResolver` 优先于 `ResponseStatusExceptionResolver`，415/400 被吞成 **500** | 加透传 handler |
| `HealthController` | `/health/**` permitAll，且把 `e.getMessage()` 塞进响应 → 泄漏 JDBC URL / 主机名 / Redis 拓扑 | 异常详情只进日志，响应只留 `status: down` |
| 视频/文档 list + detail | 直接返回实体，状态/密级是 **Integer**，前端当字符串比 → 状态标签恒错、密级列显示裸数字、**Videos 页 HLS 轮询两个分支都不进 → 每次上传必然等满 10 分钟才报"处理超时"** | 新增 `dto/ResourceEnums` + `dto/VideoResponse` + `dto/DocResponse`，list / detail 统一走 DTO |
| `DistributionController` list | 直接塞 `page.getRecords()`（camelCase 实体），前端取 `row.external_recipient.name` → `undefined.name` → **整个列表页白屏** | 逐条走 `toResponse()` |

---

## 七、前端工具链（CI 三个步骤原本全跑不通）

### 现状

`web/` 目录**没有** `tsconfig.json`、没有 eslint 配置、没有 vitest 配置、
没有任何测试文件。而 CI 的 `frontend:build` job 跑：

```yaml
- npm run lint     # → ESLint 找不到配置文件，直接退出
- npm test          # → vitest 无配置无测试
- npm run build     # → vue-tsc --noEmit 找不到 tsconfig
```

即**前端从未被类型检查过**，CI 那个 job 一直是红的（或被人忽略）。

### 修复

- 新增 `web/tsconfig.json`（strict + paths 别名 + noUnusedLocals）
- 新增 `web/tsconfig.node.json`
- 补 `.gitignore`：`web/dist/`、coverage、Playwright/Cypress 产物

---

## 验证

- 后端：`mvn -o compile` 通过
- worker：全部 `.py` 通过 `ast.parse` 语法检查
- 前端：`vite build` 通过（type check 见下一轮）

## 待办（下一轮 / 独立议题）

- 前端 43 个类型错误（tsconfig 打开后暴露）
- 前端 P0：Settings 页一进去就被登出、`client.ts` 的 `isApiCall` 恒 false、
  router 守卫无限重定向、VideoDetail 的 `errorMsg` 不复位 + `route.params.id` 无 watch
- helm：探针路径漏 `/api/v1`、缺 `serviceaccount.yaml`、JWT_SECRET 未注入、
  `DB_PASSWORD` vs `MYSQL_PASSWORD`
- CI：helm 未带 `-f gitops/overlays/*`、worker tag 未覆盖、`bc` 未装、
  worker job 双 `cd`、flyway 两次 migrate
- ArgoCD：`valueFiles` 用 `../../` 越出 source root → overlay 100% 不生效
- WebSocket `/ws` 重复注册 + 完全无鉴权
- 限流器内存无界 + `X-Forwarded-For` 可伪造
- SSO 路径匹配漏 context-path、PKCE/state 形同虚设
- 7 份重复的 `toPaginationResponse` / 3 份 `statusToString` 应收敛
- e2e 测试完全没接进 CI
