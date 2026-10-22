# 全项目审查第二轮：P1 加固 + 重复/死代码收敛 + 部署补全

- 日期：2026-10-21
- 承接：`2026-10-20-full-project-review-and-p0-fixes.md`、
  `2026-10-20-frontend-p0-and-toolchain.md`、
  `2026-10-20-deployment-p0-fixes.md`

---

## 一、后端安全 P1

### WebSocket：重复注册 + 完全无鉴权

| 问题 | 修复 |
|---|---|
| `/ws` 注册了两次，第二次 `.withSockJS()` **覆盖**第一次（`StompEndpointRegistry` 以 path 为 key）→ 原生 WebSocket 端点静默丢失 | 只注册一次 |
| `setAllowedOriginPatterns("*")` + `/ws/**` permitAll + 无 ChannelInterceptor → **任意来源的任意页面**可连上并订阅 `/topic/notifications/leak`，实时收到含 user_id、泄露文件路径、匹配置信度的告警 | 新增 `WebSocketAuthInterceptor`：CONNECT / SEND / SUBSCRIBE 全部校验 JWT；订阅走白名单正则；origin 改为配置项 `edam.websocket.allowed-origins`（留空=只允许同源，不再是 `*`） |

**注意**：`JwtAuthenticationFilter` 是 Servlet Filter，只在 HTTP 握手层生效；
STOMP 会话建立之后的 CONNECT 帧**完全不过那个过滤器** —— 所以 WS 通道必须自己校验。

### 限流器：无界内存 + XFF 可伪造

| 问题 | 修复 |
|---|---|
| 两个 `ConcurrentHashMap` 永不清理，key 是客户端可控的 IP/工号 → 伪造 IP 即可无限撑大内存 | 定时清理超过 2 个窗口未访问的桶 + 总量硬上限（`max-entries`，默认 10000）按 LRU 淘汰 |
| `ClientIpResolver` 无条件采信 `X-Forwarded-For` → 每次换一个伪造值就能绕过"单 IP 限流" | 只信任**配置白名单代理**（`edam.security.trusted-proxies`，支持 IP / CIDR）；留空 = fail-closed，一律用 socket 真实 IP |

### SSO：路径永远不匹配 + state/PKCE 形同虚设

| 问题 | 修复 |
|---|---|
| `request.getRequestURI()` 含 context-path（`/api/v1/auth/sso/...`），跟 `/auth/sso/login/` 比较**永不匹配** → 登录/回调/元数据三条路径全是死的 | 新增 `SsoRequestPaths`，用 `getServletPath()` 或手工剥离 context-path |
| `SsoCallbackParser` 切出 `api/v1/auth/sso/callback/xxx` 当 providerId | 同样走 `SsoRequestPaths.extractAfter` |
| `state` 生成了**从不存、从不验** → 登录 CSRF 无防护 | 新增 `SsoStateStore`（Redis，TTL 10 分钟，一次性）；回调时消费并校验 provider 匹配 |
| PKCE 的 `code_verifier` 生成了**立刻丢弃**，换 token 时也不发送 → 任何合规 IdP 都会拒绝 | 登录时存 state+verifier，换 token 时带上 `code_verifier` |
| SSO 成功后把 `access_token` + `refresh_token` 拼进重定向 URL → 进浏览器历史 / 反代 access log / Referer | 改成**一次性登录码**（存 Redis）+ 新增 `POST /auth/sso/exchange` 端点换 token；失败重定向不再带异常 message |

### 密码策略：唯一执行者从不被调用

- `UserController` 创建/重置密码只查 `length() < 6` → 管理员可以给用户设 `123456`
- `PasswordPolicy` 写了完整的策略（长度 / 复杂度至少 3 类 / 弱口令黑名单）但**没有任何 controller 调用**
- `PasswordService` 实现了策略 + **历史密码不可重用**（`sys_password_history`）+ 密码轮转，但**零引用** → 等保要求的"历史密码不可重用"完全没生效

**修复**：把 `PasswordService` 接线（`AuthService.changePassword` 与 `UserController.resetPassword` 都走它），
`PasswordPolicy` 通过 `validateOrThrow()` 接入创建路径。

### 其他

| 位置 | 问题 | 修复 |
|---|---|---|
| `WebhookDeliveryService` | `@Async("webhookExecutor")` 但**没有这个 bean** → 首次调用抛 `NoSuchBeanDefinitionException` | 新增 `config/AsyncExecutorConfig`（`CallerRunsPolicy` + 优雅停机） |
| `WebhookRetryConsumer` | 取出了 `attempt` 却**完全没用**，deliver 内部硬编码从 1 开始 → attempt 永远重置 → `shouldRetry` 恒真 → **无限重试风暴，永不进 DLQ** | `deliver` 增加 attempt 形参并逐次透传 |
| `PermissionService` | `@PostConstruct` 加载权限全集后**永不刷新** → 运行期新增权限对 admin 立即失效，必须重启 | 改 TTL 缓存（默认 5 分钟）+ 读路径上按需重载；DB 读失败保留旧快照 |
| `AuditService` | 审计写失败只打 error 日志，无指标 → 合规侧无法告警 | 加 `edam_audit_write_failures_total` Counter |
| `UserController.resetPassword` | 临时密码没返回给管理员，且从不写历史 | 返回临时密码（一次性）；**不写进审计 detail**（审计表审计员可读 = 二次泄露） |
| `DocumentService.list` | 收了 `classification_lv` 参数却**完全不用** → 前端密级下拉框选了等于没选 | 真正应用过滤 |
| `DocumentController.preview` | `doc.getSizeBytes()` 可空 → 拆箱 NPE，且响应头已写一半 → 客户端拿到截断响应 | 空值兜底 |

---

## 二、部署：补齐缺失的组件

### nginx + web 模板（原来**根本不存在**）

values 里定义了完整的 `nginx.*`（副本数、4Gi 限额、nginxConf），
但 templates 里**没有 nginx 模板，也没有 web 前端模板**；
ingress 只有一条 `path: /` 打到 backend → **线上访问根路径只有后端 404，前端根本不存在**。

新增：

| 文件 | 内容 |
|---|---|
| `templates/web-deployment.yaml` | 前端 Deployment（镜像自带 nginx，端口 80，探针，可写卷给 nginx cache/run） |
| `templates/web-service.yaml` | 前端 Service（ClusterIP:80） |
| `templates/nginx-deployment.yaml` | 网关 Deployment（ConfigMap 挂 nginx.conf + 探针） |
| `templates/nginx-service.yaml` | 网关 Service（LoadBalancer:80/443） |
| `templates/nginx-configmap.yaml` | nginx.conf（用 `tpl` 渲染） |
| `_helpers.tpl` | 新增 `edam.nginx.selectorLabels` / `edam.web.selectorLabels` |

**职责划分**：nginx 是唯一入口（ingress 改指 nginx），
`/api/` 与 `/ws` → 后端 Service，其余 → 前端 SPA。

**顺带修**：`nginxConf` 里的 upstream 写的是 `server backend:8080`，
但真实 Service 名是 `{{fullname}}-backend` → nginx 会一直
`host not found in upstream` 而启动失败。

**踩坑记录**：Helm 模板的**注释里不能出现双花括号** —— 会被当成模板动作解析
（`unexpected <.> in operand`）。nginx.conf 里的注释已改用 ASCII。

### 日志落盘 + 临时磁盘

| 问题 | 修复 |
|---|---|
| `readOnlyRootFilesystem: true` 而日志要写 `/var/log/edam/backend.log`，该目录不存在也没挂 volume → Logback FileAppender 静默失败，文件日志全丢 | 挂 emptyDir 到 `/var/log/edam`；`logging.file.name` 改为可配置（置空即关闭文件日志，交给采集器） |
| 8GB 上传依赖 `/tmp`（`max-file-size=8GB` + `file-size-threshold=0` 强制落盘），emptyDir **无 sizeLimit** → 直接吃节点可分配磁盘，一个大上传能把 kubelet 拖到 disk-pressure | `emptyDir.sizeLimit: 20Gi`；resources 加 `ephemeral-storage` requests 20Gi / limits 30Gi |

---

## 三、数据库迁移：双轨收敛成一套

### 现状

仓库里有**两套互不兼容**的迁移：

| | backend classpath（V1/V2/V3） | dev/db/migration（V2026*） |
|---|---|---|
| 表数量 | 20 | **35**（超集） |
| `sys_user_role` 过期列 | `expires_at` | `expire_at` ← 与 model 不符 |
| `sys_permission` | 有 `action`/`description`/`is_system`/`created_at` | **全缺** |
| `sys_role_permission` | 有 `constraint_def`/`created_at` | **全缺** |
| `mime_type` | 已拓宽 255 | 还是 64（Excel MIME 66 字符会截断） |
| RBAC 种子数据 | 45 权限 + 4 角色 | **完全没有** |

任何一份先跑、另一份后跑都会因 `validate-on-migrate` 失败；
而且 Java model 只跟其中一套对得上。

### 收敛方案

**权威 = dev/db 的表结构（35 表）+ backend 的 RBAC 数据**。
RBAC 数据以**代码为准**（`@PreAuthorize` 用 `admin:backup`/`distribution:approve`，
`isAdmin()` 判角色码 `'admin'`），而不是 seed 里那套 `super_admin`/`system:backup`。

结果：

```
backend/src/main/resources/db/migration/
  V20260812_1000__init_schema.sql              （35 表，已修列名/宽度）
  V20260820_1000__add_soft_del_and_session.sql
  V20260825_1000__add_password_history.sql
  V20260826_1000__add_webauthn.sql
  V20260827_1000__add_fingerprint_library.sql
  V20260828_1000__add_data_classification.sql
  V20260830_1000__rbac_seed.sql                 （45 权限 + 4 角色 + 分配 + 用户绑定）
  V20260830_2000__align_model_columns.sql       （补 video_resource.updated_at / webhook_delivery.created_at）
```

- 旧 V1/V2/V3 已删
- `dev/db/migration/` 留 README 说明已废弃
- `dev/db/rollback/` 放 `U*.sql`（手工回滚，Flyway Community 不自动跑 undo）
- `dev/db/seed/` 去掉权限目录（归迁移管），角色码改成与代码一致
- CI 的 flyway locations 指向唯一权威目录

**静态验证**：写了脚本把 20 个 `@TableName` model 的 **172 个字段**
逐一映射到 schema 列 —— 全部匹配（这一步查出了
`video_resource.updated_at` 与 `webhook_delivery.created_at` 两处真缺列）。

---

## 四、E2E：接进 CI（此前从没跑过）

### 现状

`e2e/` 里有 playwright + cypress 两套测试，但**CI 完全没接**。
而且 playwright 配置本身有 4 个错，套件**一次都没成功执行过**：

| 问题 | 修复 |
|---|---|
| `testDir: './tests'` —— 实际文件在 `./playwright/tests/` → "No tests found" | 改对路径 |
| webServer 的 mock 路径用 `process.cwd()` 拼 → 拼出 `e2e/dev/mock/server.py`（不存在） | 改用 `path.resolve(__dirname, '../dev/mock/server.py')` |
| CI 分支走 `docker run stoplight/prism:5` —— job 镜像里没有 docker | 本地和 CI 都用 Python 轻量 mock（零第三方依赖） |
| Windows 上 `python3` 是 Store 别名（exit 9009） | 按平台选 `python` / `python3` |

### mock server 补齐

9 个失败全是 mock 缺功能：

- 登录限流（429）与账号锁定（423）
- 受保护端点的 401（无 token）
- `GET /distribution/approvals/{id}`（404）
- `decide` 是无状态空壳（永远返回 approved 但不改数据）
- `revoke` 不改状态
- `/videos` 不按 `classification_lv` 过滤
- `/watermarks/extract` 固定返回 `type="image"`（不管请求要 pdf 还是 video）
- `GET /watermarks/cache`（404）
- multipart 请求体一律按 JSON 解析 → 抛异常（补了 multipart 字段解析）

### 结果

```
19 passed / 1 skipped / 0 failed
```

CI 新增 `e2e:test` job（`mcr.microsoft.com/playwright:v1.45.0-jammy`，
自带 Node + Python3 + 浏览器，免去 CI 里再装浏览器；只跑 chromium）。

---

## 五、重复代码与死代码收敛

### 分页响应：7 份 → 1 份

新建 `dto/PaginationResponses`（`of(Page)` / `of(Page, mapper)` / `of(list, ...)`），
7 处逐字相同的分页壳全部替换：

`VideoController` / `DocumentController` / `DistributionController` /
`UserController` / `AuditController` / `SearchController`×2

### 死代码分类处理

| 类 | 引用 | 处理 | 理由 |
|---|---|---|---|
| `PasswordService` | 0 | **接线** | 实现比代码里的更完整（历史密码 + 轮转），接上后等保要求才生效 |
| `SysSessionRepository` | 0 | 保留（表在 schema 里，repository 无害） | refresh token 实际存 Redis，repository 是备用 |
| `SmJwtTokenProvider` + `crypto/`（684 行） | 0（内部互引） | **标 `@Deprecated`** | 国密是方案书/等保的既定要求，属"写好未接线"，删除是损失 |
| `ClassificationEnforcement` | 0 | 标 `@Deprecated` | 自动密级打标（W-7.2/W-7.3）已规划 |
| `crypto/gmsm/SM2Util` `SM3Util` `SM4Util` | 仅被 crypto 链引用 | 标 `@Deprecated` | 同上 |

### 前端

- 新增 `utils/dialog.ts` 的 `isDialogDismissed(e)`，替换 7 处 `e !== 'cancel'`
  —— Element Plus 点右上角 × reject 的是 **`'close'`**，原来会走进错误分支
  弹"删除失败：undefined"

---

## 验证

```
后端   mvn -o compile                     通过
前端   vue-tsc --noEmit                   0 error
前端   npm run lint                      0 error / 11 warning
前端   npm test                           15 passed
前端   npm run build                      成功
e2e    playwright test --project=chromium  19 passed / 1 skipped
Helm   lint + template × 3 overlay       0 failed / 全部渲染成功
CI     yaml.safe_load                    12 jobs 解析成功
DB     model 字段 ↔ schema 列静态核对     172/172 匹配
```

按本项目 git-auto-commit 约定自动 commit + push。
