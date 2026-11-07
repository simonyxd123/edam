# 更新日志（Changelog）

本项目所有重要变更记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/) 规范。

## [未发布]

### 计划中
- v3.4 中期（W5-W18）：等保测评现场执行 + 商密许可颁发 + 频域水印生产化灰度
- AI 辅助文档审核（敏感信息自动识别 + LLM 二次校验）

---

## [3.4.1] - 2026-11-07

> 本版本修掉 v3.4.0 盘点出的**真问题** —— 不是文档，是代码里真实存在的洞。
> 主题：把「权限从哪来」「密级谁说了算」这两件事钉死。

### 修复

#### WebAuthn / SSO 登录绕过 RBAC（最要紧）

- 此前 `WebAuthnController` 与 `SsoUserProvisioning` 签发 JWT 时**写死**了
  角色 `ROLE_EMPLOYEE` 与三条固定权限码（`dashboard:read` / `video:read` /
  `document:read`）。JWT 里的 `permissions` 就是 `@PreAuthorize` 的唯一判据 ——
  写死等于这两条登录路径**完全绕过了 RBAC**：
  - 管理员用 WebAuthn 登录 → 退化成只有 3 条读权限的普通员工
  - 反过来，一个在 RBAC 里没有任何权限的用户反倒拿到了那 3 条
- 新增 `TokenIssuanceService`：四个签发点（密码登录 / 刷新 / WebAuthn / SSO）
  统一走它，角色与权限码一律读 `sys_user_role` → `sys_role` →
  `sys_role_permission` → `sys_permission`
- 账号状态检查（锁定 / 禁用）抽到 `LoginAttemptService`，三条登录路径共用 ——
  此前 WebAuthn / SSO 完全没查状态，离职账号照样能换到有效 JWT
- WebAuthn 登录补 MFA 校验、补 `refresh_token` 落 Redis（此前只生成不存，
  `/auth/refresh` 必然失败）、补登录限流（端点匿名，不限流就能枚举工号）
- SSO 新开通用户在 RBAC 表里真的给一个角色（此前一个角色都没有）
- `SecurityConfig` 放开 `/auth/webauthn/login/**`，与 openapi 契约对齐

#### 密级由客户端单方面声明

- 此前上传时 `classification_lv` **一个字节都没校验**，非法值还会被
  `default -> 1` 静默降级成 **L1 公开** —— 任何人都能把自己的资产声明成公开
- 服务端裁决已接线：最终密级 = **max(服务端基线, 客户端声明)** ——
  客户端可以往严里选，不能往松里选
  - 基线 = 文件名关键词 / 文件大小 / 上传者部门（新增 `SysDept` 实体 ——
    Java 侧此前**没有**部门模型，"按部门定密级"这条规则等于从未生效）
  - 自动识别**封顶 L3**：L4 绝密只能由人显式声明或事后人工调整，
    否则一个 6GB 培训视频会变成谁也看不见的资产
- 非法密级直接 400（Controller 入口 + Service 双重校验），不再静默降级
- 部门规则改**包含匹配** —— 原先是全等匹配（`"法务"`），
  而 `sys_dept` 里叫「法务部」，一条都命中不了
- 新增 `PATCH /classification/{videos|documents}/{id}` 人工调整密级
  （门 `classification:manage`，此前该权限码 Java 侧零引用）；
  调整原因必填，全程写 `data_classification_audit`
- 上传响应回传**实际落库**的密级，前端在服务端抬高时如实提示用户
  （不能让用户以为声明的 L1 生效了）

#### 文档处理状态不回写 / 回调 URL 拼错

- **回调 URL 拼成了 `/api/v1/api/v1`** —— `BACKEND_BASE_URL` 在 helm / ops 的
  env 里带了 context-path，代码又习惯性地拼一次 `/api/v1` ⇒ 404 ⇒
  被当成"其它 4xx" ⇒ `PermanentWorkerError` ⇒ **视频状态永远不回写**，
  转码明明完成了，`hls_status` 却一直停在 pending，用户点播放还是 404。
  新增 `config.backend_api_url()` 统一收口，两种写法都对
- **文档处理根本没有回调端点** —— Worker 处理完不回写，
  `watermark_status` / `preview_status` 永远停在 pending，
  前端的状态标签恒为 pending。新增 `PATCH /documents/{doc_id}/status`
- 水印与预览两个状态**分开**报 —— "水印成功但预览失败"是常见情况，
  一刀切报一个状态会把已经做完的那半也标成失败
- 状态与路径**必须在上传成功之后**才置 ready —— 否则会把一个没传上去的
  产物报成"已就绪"
- Worker 回调令牌校验抽成 `WorkerTokenVerifier` —— 视频侧与文档侧共用，
  这类安全关键代码不能复制两份（改了一处忘了另一处就是静默敞开的后门）
- 新增迁移 `V20261106_1000__doc_watermark_path.sql`：
  `doc_resource` 补 `watermark_path` 列 —— 水印产物的位置此前无处可记

#### 死代码与"两套不兼容实现"

- **`q.watermark` 队列从来没有生产者** —— 已声明、已绑定、已被 Worker 消费、
  已被 Prometheus 监控，但后端从不往 `edam.tasks` 的 `watermark` 路由发消息。
  真实的盲水印嵌入走的是 `q.document.preprocess`
  （`DocumentProcessor._watermark` → `WatermarkProcessor.embed_*`）。
  留着它，运维看队列监控会以为这条链路是活的 ⇒ **整条支线删除**
  （后端队列/绑定/监控项 + Worker 消费者 + `WatermarkProcessor.process()`）
- **pHash 有两套互不兼容的实现** —— `processors/video.py` 用 imagehash
  （DCT + 缩放 + **高通滤波**），`fingerprint/PHashService.py` 自己手写 DCT
  （**没有高通滤波**，中位数取法也不同）。
  两边算出的 bit 串对不上 ⇒ 汉明距离比较毫无意义，**匹配率 ≈ 0**，
  而且**不报任何错** —— 看起来跑通了，只是永远匹配不上。
  ⇒ 新增 `fingerprint/phash.py` 作为**唯一实现**，
  `processors/video.py` 与 `PHashService` 都调它，产出**逐位相同**。
  `fingerprint.json` 的存储格式（16 位小写 hex）**保持不变** ——
  已落盘的数据靠它，改格式会让历史指纹全部失效
- **盲水印也有两套互不兼容的实现** —— `DocWatermarkService` 用
  blind-watermark 的 `mode="bit"`，`processors/watermark.py` 用 `mode="bytes"`：
  **bit 模式嵌进去的水印，bytes 模式提不出来**（反过来也一样）。
  而真实链路走的是后者 ⇒ `DocWatermarkService` 就算被接上，产出的水印也提不出来。
  ⇒ `DocWatermarkService` 改为委托 `WatermarkProcessor`，嵌入与提取走同一套
- **泄露比对此前零引用** —— 新增 `POST /ops/leak-detect`（受 `OPS_TOKEN` 保护）：
  疑似泄露文件 ↔ 参考视频的帧指纹比对
- 修 `LeakDetectionService` 里的 `logger.warn` —— logging 没有 `warn` 级别，
  会打一条 "No such level" 警告，把真正的检测日志淹掉

#### 盲水印提取：把"假没查到"换成真提取

- 此前 `POST /watermarks/extract` 返回的是一个**假的**"没查到泄露"：
  `confidence=0.0` + `matched_users=[]`，而且**什么都没扫过**。
  那比不提供这个端点更危险 —— 运维看到"没有匹配用户"，
  会得出"这个文件是干净的"这个**错误结论**。
- 现在真的调 Worker 提取（盲水印算法在 Python 侧）：
  - Worker 新增 `/internal/watermark/extract`（受 `WORKER_TOKEN` 保护，
    fail-closed；请求体是文件原始字节，不用把溯源样本落进 MinIO）
  - 后端新增 `WatermarkExtractClient`（新增配置项 `edam.worker.base-url`）
  - 按水印文本 `{上传者ID}|{文档ID}|{时间戳}` 反查文档与上传者，
    返回真实的 `matched_users`
- 三态必须分清（有测试钉住）：
  - 提取到水印 → `extracted.fingerprint` 有值 + 匹配用户
  - 文件里没有水印 → `extracted=false`（**有效结论**）
  - 提取过程失败（Worker 不可达 / 算法缺失 / 文件损坏）→ **503 + 明确原因**，
    绝不伪装成"没查到泄露"
- 当前支持 `image` / `pdf`；`video` / `docx` / `xlsx` 明确报 400
  （帧指纹比对与 Office 隐写提取尚未接线）

#### 通知 / Webhook：从"假返回"到真读写

- **重试消息路由键不匹配 → 消息被静默丢弃**（真 bug）：
  `scheduleRetry()` / `sendToDlq()` 把**队列名**当路由键发出去
  （`convertAndSend(exchange, queueName, ...)`），而绑定用的是
  `edam.webhook.retry` / `dlq` —— 路由键对不上，broker 静默丢弃
  （未开 mandatory 不报错）。结果：**重试从未发生过，失败的通知直接消失**。
  现改用正确的路由键常量。
- **退避延迟从来没生效** —— 消费者忽略了 payload 里的 `retry_at_ms`，
  消息一到就立刻重试。新增**按退避档位的延迟队列**（每个档位一个固定
  TTL 的队列，到期后由 broker 死信回重试队列）。
  不用"一个队列 + per-message TTL"是因为那会**队头阻塞** ——
  一条 12 小时的延迟消息会把后面 1 分钟的消息堵住 12 小时。
- **回调注册是假的** —— `POST /webhooks` 造一个假 secret 和假 ID 返回 201，
  **什么都不落库**。现在真落库（新增 `Webhook` model + repository）：
  secret 明文只在创建响应里返回一次，库里存 SHA-256 哈希
- **回调地址缺 SSRF 防护** —— 服务端会主动 POST 过去，
  现在拒绝指向内网 / 回环 / 元数据服务的地址
- **注销 / 读投递历史缺归属校验** —— 任何人都能注销别人的回调、
  读别人的投递历史。现在一律带 owner 条件，命中不到返回 404
- **通知偏好是原样回显** —— `PUT /notifications/preferences`
  直接把请求体返回，用户以为设置保存了，其实什么都没落库。
  现在真写 `notification_preferences` 表（新增 model + repository），
  回读的是**落库后**的值
- **通知列表 / 标记已读 / 全部已读** 此前也是写死的空返回，
  现在按 `notification` 表真查真改（标记已读带 user_id 条件）
- 仍未接线（已写进 README 的「已知限制」）：投递触发入口（没有任何事件
  会触发回调 —— 签名需要 secret 明文而表里只有哈希）、通知的生产者

#### 国密链路：接上了（JWT 签名）

- 此前 `SmJwtTokenProvider` **完全不能用**，三个真 bug：
  1. `parseSm3Token` 调 `Jwts.parser().parseSignedClaims(token)` ——
     jjwt 不认识 `GS256`，**任何 GS256 token 都解析不出来**
  2. claim 名对不上：写的是 `sid`，`JwtAuthenticationFilter` 读的是
     `session_id`；而且**根本没有 permissions claim** ⇒
     所有 `@PreAuthorize` 都会 403
  3. payload 用 `String.format` 手工拼 JSON，角色码里的 `"` 与 `\`
     没有转义 ⇒ 能拼出非法 JSON
- 而且它**零调用** —— `AlgorithmRouter` 与 `SmJwtTokenProvider` 都标着
  `@Deprecated(since="3.4.0")`，一段永远跑不到的代码怎么测都"绿"
- 现在的接线方式：`edam.jwt.algorithm=GS256` 时，`JwtTokenProvider`
  内部把签发与验签都交给国密 provider ——
  **所有调用方一行都不用改**（签发、播放 token、WebSocket 握手全都不动）
- 验签按 token header 里的 `alg` 派发，而不是按当前配置 ——
  这样从 HS256 切到 GS256 的过程中，已签发的旧 token 仍可用，
  不会把在线用户全踢下线
- `JwtAuthenticationFilter` 原先**自己**解析了一遍 JWT（固定 HMAC-SHA256），
  签发端切国密它也不认 ⇒ 现在收敛到 `JwtTokenProvider`，两边必然一致
- payload 改用 Jackson 序列化（自动转义），签名算法经 `AlgorithmRouter` 选取
- 补 `exp` 过期校验 —— 手工解析绕开了 jjwt 的过期检查，
  不自己查就等于**永不过期的 token**
- 配置项 `edam.jwt.algorithm` / `edam.crypto.use-gmsm` 此前**在 yml 里
  根本不存在**（只有 `@Value` 的默认值兜底），现已显式声明
- 仍未覆盖：存储层加密仍统一走 AES-256-GCM，没有按"密级 L3+ 强制国密"
  切到 SM4；国密 JWT 用的是普通 SM3-HMAC，**不是商密合规的密码模块**

### 变更

- 权限码是**登录那一刻**的快照 —— 改了角色必须重新登录才生效（管理员手册已提醒）
- 端点数 95 → **98**（新增 2 个密级调整端点 + 1 个文档状态回调端点）
- 数据库迁移 14 → **15**（新增 `V20261106_1000__doc_watermark_path.sql`）

### 验证

- 后端 161 → **219** 用例，0 失败（6 个 error 仍是既有的 `ApplicationStartupTest`，
  需要真实 MySQL，与本次无关）
- 前端 58 用例全绿；`npm run build` 内置 vue-tsc 类型检查通过
- Worker 68 → **101** 用例（99 通过 / 2 跳过，缺 ffmpeg 与 PyPDF2）
- 端点对账 95 ↔ 95 → **98 ↔ 98 / 100%**

---

## [3.4.0] - 2026-11-05

> 本版本是 v3.3（2026-08-28）之后的**第一个完整发版**。
> 期间共 90 次提交，主题集中在三件事：
> **授权模型落地**、**审计闭环**、**播放链路可靠性**。

### 新增

#### 授权模型（RBAC + 资源级 ACL 完整落地）

- **资源级 ACL 落地**：`video_permission` / `doc_permission` 两张表真正生效
  - 可见性判定：admin → 上传者 → 显式授权（优先于密级）→ L1/L2 → L3 同部门 → L4 拒绝
  - 动作位掩码：视频 `view/download/edit/delete/distribute`；文档额外 `print`
  - 授权有效期（`expire_at`）、**覆盖而非叠加**语义、未知位拒绝
  - 列表接口按可见性过滤（`applyVisibility`），不再"全量返回再前端过滤"
  - 越权分享阻断：无 `distribute` 位不能分享别人的资源
- **文档侧授权模型 + 前端分享界面**（`77c3143`）
  - 新增统一的 `ShareDialog` 组件，视频 / 文档共用
  - 动作位在前端与后端契约一致（`web/src/api/permission.spec.ts` 锁住）
- **用户管理 + 角色 CRUD 完整版**（`e32af40`）：增 / 改 / 删 / 重置密码 / 分配角色
- **Excel 批量导入用户**（`0d10bc0`）：前端 xlsx 解析，导入时同步绑定角色

#### 审计（从"关键操作留痕"到"全量操作留痕"）

- **`@AuditOp` 注解 + AOP 切面**（`9d9a624`）：全量操作自动留痕
  - **三态留痕**：成功 / 失败 / **拒绝**（越权访问同样入账）
  - `AuditAccessFilter` 兜底：没进控制器就被 401/403 挡下的请求也留痕
  - 敏感键名自动打码（密码 / token / secret / mfa_code …，约 30 个键）
  - `operation_log` 扩列：`http_method` / `endpoint` / `status_code` / `duration_ms` / `session_id`
  - `user_id` 改**可空** —— 未登录的登录失败、越权尝试也要能记下来
  - **日志中不得打印 token 本身**（连哈希都不打）
- **审计切面不再靠 Spring 绑注解**（`6f86240`）：修上线后全站 500
  - 原实现用 `@annotation(auditOp)` 参数绑定，与 `ExposeInvocationInterceptor` 顺序冲突
  - 改为切面自行从 `MethodSignature` 解析注解，切面 order 提到 `HIGHEST_PRECEDENCE`

#### 普通员工收敛为只读（本次发版的重点）

- **员工基线重定义**（`7284da5`）：普通员工**只能播放 / 预览**，不能上传、不能删除
  - 权限目录 46 条；employee 白名单 10 条，全是 `action='read'`
  - 用**白名单**而非"删掉 upload 那几条" —— 新权限默认不给员工，杜绝权限漂移
  - 权威来源 `EmployeePermissions.java`，由 `EmployeePermissionsTest` 保证与迁移 SQL 逐字一致
  - 迁移 `V20261105_1000`：补 `watermark:manage`；employee 重置为白名单；
    dept_manager 补 `video:delete` / `document:delete`（原先能上传却删不掉自己传的东西）
- **前端按权限隐藏按钮**：`v-permission` 指令从"删元素"改为"隐藏"
  - 删元素会和 Vue 虚拟 DOM 打架（vnode 还指着那个节点，后续 patch 抛 `removeChild`）
  - 支持数组语义（任一命中即可），留下 `data-permission-denied` 标记便于排障
- **顺手堵上的越权口子**（同一批改动）：
  - `POST/DELETE /webhooks` —— 回调 URL 由客户端指定 = SSRF / 数据外传通道
  - `DELETE /watermarks/cache/{id}` —— 权限目录里压根没有对应权限码
  - `GET /distribution/approvals` —— 改为**只能看自己提交的**
  - `POST /distribution/approvals` —— 只能对**自己看得见**的文档申请外发
- **`/settings` 对所有登录用户开放**：原先路由挂着 `system:read`，
  普通员工**连自己的密码都改不了**。现在整页开放，「系统配置」Tab 单独按 `system:read` 收口

#### 播放链路可靠性

- **分片地址不再猜 origin**（`a1cc0c2`）：换个账号就播不了的根因
- **密钥地址不再只认 key.bin**（`f8b2668`）：存量播放列表拿不到密钥的根因
- **第二次播放的密钥失步**（`422439e`）：重试改重建播放器，别用 `startLoad`
- **HLS key 缓存重试状态机**（`2026-11-03`）+ **key URI 改写修正**
- **播放 token 绑定 video_id + scope**：`m3u8` / `segment` / `key` 三个 permitAll 端点
  只靠 query token，普通登录 token 会被拿来拉任意视频
- **404 与 503 分开**：前者是"产物不存在"，后者是"对象存储挂了" —— 前端处置方式不同

#### 部署与工程

- **端口统一到 8092**（12 个文件）+ MySQL 对外端口统一 3307（容器内仍 3306）
- **Flyway 收敛**：`dev/db/migration/` 的第二套迁移下线（曾导致 `validate-on-migrate` 失败）
- **Flyway failed migration 修复** + 外键 signed/unsigned 不兼容（Error 3780）修复
- **Worker 进程守护 + 队列卡死告警** —— 别再等用户点播放才发现
- **Worker 运维接口**：按 video_id 重新触发 HLS、探测 MinIO 文件存在性
- **健康探针真的会失败**（503）+ 未认证返回 401（原先恒返回 200）
- **CI 从全红到全绿**：后端测试、e2e、db:migrate 三个 job 修复
- **上传上限 2GB → 8GB**，连带修复被 30s 超时掐断的问题
- **WebAuthn / FIDO2 端点补进 OpenAPI**（6 个，此前完全没写进规范）

### 变更

- **版本号统一到 3.4.0**：`backend/pom.xml` / `web/package.json` / `worker` / `helm` /
  `application.yml` / `openapi.yaml` / `e2e` 此前分别是 3.1.0 / 3.3.0 / 3.1.0 / 3.1.0 / 3.1.0 /
  3.1.0 / 3.3.0 —— 现在全部一致
- **Helm Chart**：version 1.0.0 → 1.1.0，appVersion → 3.4.0
- **OpenAPI 规范**：74 paths / 95 端点 / 17 tag / 40 schema
  - 修复 **4 组重复 path 键**（YAML 只保留后一份 ⇒ 前一份是死代码，
    其中 `/playback/*/token` 的旧定义描述的是过时的 `X-Session-Token` 头方案）
  - 补 `x-required-permission` 扩展（46 个端点的角色门此前完全没写进规范）
  - 补 `PermissionDTO` / `VideoResource` / `DocResource` 等缺失 schema
- **数据库表数**：26 → **35 张**；Flyway 迁移：8 → **14 个**
- **权限目录**：34 → **46 条**（补 `watermark:manage` / `system:read` / `system:manage` 等）
- **测试规模**：后端 161 用例 / 前端 58 用例 / Worker 68 用例 / E2E 6 个 spec
- **普通员工**：从"可上传可预览"改为**只读** —— 行为变更，需重新登录
  （JWT 里的权限码是登录时签发的，旧 token 仍带 `video:upload`）

### 修复

见上方各节。影响面最大的几条：

- 审计切面导致**上线后全站 500**
- 分片地址猜 origin 导致**换个账号就播不了**
- 密钥地址只认 key.bin 导致**存量播放列表拿不到密钥**
- 第二次播放密钥失步
- Worker 从 10-23 起**启动即崩**（实际从没起来过）
- 审计日志全丢 + Worker 回调被拒（两个静默失败）
- 视频/文档列表的时长、大小、上传时间全为空（Jackson 命名策略）

---

## [3.3.0] - 2026-08-28

### 新增
- **等保三级 P0 整改（W-1）**：9 项全部落地
  - WAF 部署（ModSecurity 规则集 OWASP CRS 3.3）
  - SOC/态势感知接入（OSSIM 聚合 + ELK 告警路由）
  - 集中日志审计（ELK + WORM 180 天保留 + 防篡改签名）
  - 终端 EDR（自研 Agent + 心跳上报）
  - 密码策略升级（PAM 模块 + Vault Transit 强校验）
  - 密钥生命周期文档（生成/分发/存储/轮转/销毁 全流程）
  - 应急预案 + 红蓝演练（数据泄露 / Vault 故障 / 越权三类剧本）
  - 漏洞扫描 + 渗透测试（Trivy + DefectDojo 双引擎）
  - 个人信息影响评估（PIA）报告 V1.0
- **国密算法集成（W-2）**：SM4/SM3/SM2 完整链路
  - SM4-CBC HLS 切片加密（L3+ 密级强制）
  - SM3 JWT 签名替代 HMAC-SHA256
  - Vault Transit SM4-GCM 双轨（灰度轮转）
  - 数据库敏感字段 SM4 加密层
  - 国密 TLS（GM/T 0024）握手实现
  - 算法路由（按密级 + 策略自动选择国密/国际）
- **商密使用许可申请材料（W-3）**：6 份完整文档
  - 信息系统密码应用方案（38 页）
  - 商密使用方案评估报告（24 页）
  - 风险分析与处置报告
  - 密钥生命周期管理规范
  - 应急处置预案
  - 申请材料清单 + 进度跟踪表
- **SSO 集成（W-4）**：双协议完整支持
  - SAML 2.0 SP 端（对接 Azure AD / Okta / Authing 等 6 个 IdP 已验证）
  - OIDC RP 端（PKCE + ID Token 验签）
  - JIT Provisioning（首次登录自动建账号 + 默认角色）
  - 会话与 SSO 状态同步（单点登出广播）
- **WebAuthn / FIDO2 无密码登录（W-5）**：AAL3 等级
  - 注册流程（challenge 生成 + 凭据存储 + 备份码）
  - 登录流程（assertion 验签 + UV 要求）
  - 凭据管理（列表/重命名/撤销/重新登记）
  - 多设备支持 + 跨浏览器兼容（Chrome/Safari/Firefox/Edge）
- **频域水印生产集成（W-6）**
  - pHash 指纹库（每视频 30+ 帧 + 汉明距离阈值 5）
  - DCT 文档水印服务（嵌入 + 提取 + 加密存储）
  - 泄露检测接口（上传泄露文件 → 命中用户）
  - Worker 异步任务集成（RabbitMQ 消费 + 重试）
- **数据分类分级（W-7）**：L1-L4 强制打标
  - 自动识别引擎（关键词 + 正则 + ML 分类器三路投票）
  - 强制打标中间件（上传/审批/外发三处拦截）
  - 变更审计（标签修改全链路记录）
  - 与 RBAC 联动（按密级自动收紧权限）
- **ES CDC 同步（W-10）**：实时近一致
  - Canal 监听 MySQL binlog（ROW 模式）
  - Kafka 异步分发（解耦 + 削峰）
  - ES 批量索引（5 秒 flush + 重试）
  - 一致性校验脚本（每日全量对账）
- **移动 SDK v1.1（W-11）**：原生双端
  - iOS Swift：文档预览（PDFKit）+ 离线缓存（CryptoKit 加密）
  - Android Kotlin：文档预览（PdfRenderer）+ 离线缓存（EncryptedFile）
  - 录屏检测（iOS UIScreen.capturedDidChangeNotification + Android MediaProjection 监听）
  - 越狱/Root 检测（基础特征 + 启发式）
- **ArgoCD 多环境 GitOps（W-12）**
  - ApplicationSet 模板（dev/staging/prod 共享）
  - Kustomize overlays（环境差异：副本数/资源限制/镜像 tag）
  - Sync Wave 控制（依赖顺序：DB → Backend → Worker → Web）
  - manual-approve + health check（prod 环境）
- **SBOM + 漏洞扫描（W-13）**
  - CycloneDX SBOM 生成（CI 自动产出）
  - Snyk + Trivy 双引擎扫描
  - DefectDojo 聚合 + 漏洞生命周期
  - 高危漏洞 24h 修复 SLA
- **第三方安全咨询 RFP（W-14）**：模板就绪
  - STRIDE 35 条威胁复审
  - 红蓝对抗 + 白盒/黑盒渗透测试
  - 等保差距分析 + 代码审计
  - 整改建议矩阵
- **性能压测 + 容量规划（W-15）**
  - k6 脚本（核心 API + 鉴权 + 视频播放 + 文档下载）
  - Prometheus + Grafana 实测看板
  - SLO 基线：P50<100ms / P95<200ms / P99<500ms / QPS≥200 单 Pod

### 修复
- BouncyCastle 国密未商用认证（已在合规说明中标注 R-3：v3.4 切换商用 SDK）
- SM4 软件性能不达标（已在合规说明中标注 R-4：商用 SDK 硬件加速）

### 变更
- 后端 Spring Boot 3.3 → 仍 3.3.x；MyBatis-Plus 3.5.7 → 3.5.9
- 安全策略由 6 层纵深升级为 7 层（新增"国密合规层"）
- 合规对齐标准新增：GB/T 39786 第三级、NIST 800-63B AAL3

---

## [3.2.0] - 2026-08-12

### 新增
- **后端 Controller 100% 覆盖（V-1）**：从 43.1% → **100.0%**（65/65 端点）
  - 新增 7 个 Controller：Distribution / Webhook / Notification / Search / Tag / Permission / Admin
  - CI 强制覆盖率阈值 ≥ 95%（`scripts/check_controller_coverage.py` + `.github/workflows/ci.yml`）
- **Flyway 数据库迁移规范（V-2）**
  - 命名规范：`V{YYYYMMDD}_{HHMM}__{description}.sql`
  - 演进迁移：`U{YYYYMMDD}_{HHMM}__{description}.sql`
  - 工具链：Flyway 10.x + gh-ost/pt-osc（在线 DDL）
  - 已应用 2 个 V + 1 个 U 迁移文件
- **登录限流（V-3）**：Bucket4j 三维度
  - IP 维度：5 次/分钟（防撞库）
  - 工号维度：3 次/分钟（防账号爆破）
  - 图形验证码：连续 2 次失败后强制
  - 账号锁定：连续 5 次失败锁定 30 分钟
- **CI 一致性扫描（V-10）**：GitHub + GitLab 双 CI 同源
  - 共享 lint/format 脚本
  - 镜像 tag 同步策略
  - 流水线阶段对齐
- **cursor-based 分页（V-4）**
  - `CursorUtil` 工具类（加密 + 解码 + 边界校验）
  - 全列表接口改造（videos/documents/users/audit）
  - 性能：1000 万级数据 P99 < 50ms
- **sys_role 软删除（V-5）**
  - `@TableLogic` 注解 + 全局拦截器
  - Flyway 迁移新增 `deleted` / `deleted_at` 字段
- **Webhook 重试机制（V-6）**
  - 指数退避策略（1s/2s/4s/8s/.../1h 上限）
  - 死信队列（DLX + 人工介入）
  - 投递历史 + 失败原因结构化记录
- **ES 索引策略（V-7）**
  - `edam_resources_mapping.json`（videos/documents 索引模板）
  - 中文分词（IK Analyzer）
  - 索引别名 + 滚动策略（按月）
- **sys_session 表（V-8）**
  - 服务端会话（替代纯 JWT 的吊销难题）
  - 滑动过期（活动续期）
  - 多端会话互踢
- **移动端原生 SDK v1.0（V-9）**：iOS + Android
  - iOS Swift：`EDAMPlayer`（AVPlayer 封装 + HLS 播放 + 水印渲染）
  - Android Kotlin：`edam-player`（ExoPlayer 封装 + 水印渲染）
  - 动态 Token 鉴权 + Secure Link URL 拼接
  - 设备指纹采集（黑名单 + 风控）

### 修复
- 后端 Controller 覆盖率脚本裸注解识别（`@RestController` vs `@Controller` 双扫描）
- 部分接口缺少 trace_id 透传（已在 OpenAPI Error schema 强制）

### 变更
- 数据库表数：22 → 26 张（新增 Notification / Webhook / WebhookDelivery / Backup）
- OpenAPI tags：14 → 16 个
- 移动端从"未支持"升级为"原生 SDK v1.0"

---

## [3.1.0] - 2026-08-12

### 新增
- **OpenAPI 规范扩展**：从 30 端点扩展到 51 端点；Schema 从 26 扩展到 36
  - 健康检查细化为 4 端点（live/ready/components/health）
  - 新增 WebSocket 实时通知 `/ws/notifications`
  - API 版本管理策略文档化（12 月维护期 + 6 月并行期）
  - 登录限流策略（IP 5/min、工号 3/min、图形验证码、账号锁定）
  - 批量操作（videos/documents batch-delete, batch-permission）
  - 全文搜索（/search/videos, /search/documents）
  - 标签管理（/tags CRUD + /videos/{id}/tags）
  - 通知消费端（/notifications 列表 + 标记已读 + 偏好）
  - 备份与恢复（/admin/backups 含 MFA + 二级审批）
  - Webhook 回调注册与投递历史
  - 关键接口增加 example 字段；Error schema 增加 trace_id
- **ER 文档**：从 9 章扩展到 18 章
  - 数据库迁移规范（Flyway、命名、gh-ost/pt-osc）
  - 时区与精度统一规范（UTC DATETIME(3)、前端 dayjs）
  - 应用层加密策略（Vault Transit、密钥轮转）
  - 软删除与乐观锁规范
  - 索引使用率监控
  - 4 张新表：Notification / Webhook / WebhookDelivery / Backup
- **方案书评审闭环**：3 份评审报告识别问题全部修复
- **drawio 源文件**：4 个 v3.0 修复
  - 修复样式字符串属性重复 bug
  - ER 图关系连线加 1:N 箭头方向（endArrow=ERmany）
  - 时序图生命线虚线规范化（dashPattern=8 8）
  - 改进图例（带色块）

### 修复
- MD5 在示例与术语表的自相矛盾（推荐 Nginx 1.21+ secure_link_md5_sha）
- 移动终端策略缺失（5.1.7 节新增 iOS/Android MDM 方案）
- 驱动失效兜底机制缺失（心跳 + 文档外发网关 + 巡检 Agent 三重）
- 合规告知拒签处理流程缺失（HR + 法务联合处理）
- 工期数字不一致（统一为 35-49 周）
- 人力估算虚高（按"角色 × 阶段投入系数"重算）
- 等保测评价格偏低（调整为 20-30 万）
- Vault 故障降级方案缺失（Raft 集群 + 会话密钥缓存）
- 文档溯源后法律衔接缺失（5.3.5 节新增）

### 变更
- 文档结构从 9 章扩展到 18 章
- drawio 图从 4 个增加到 88+ cells
- OpenAPI tags 从 10 增加到 16

---

## [3.0.0] - 2026-08-12

### 新增
- **技术方案书 v3.0 终稿**：12 万字，涵盖：
  - 12 章核心内容（项目背景/架构/技术栈/视频模块/文档模块/异步流水线/数据库/安全策略/部署运维/优势风险/实施计划/总结）
  - 6 类核心模块（鉴权/播放/上传/审批/水印/管理）
  - 7 层纵深防御体系
  - 4 级密级模型（L1-L4）
  - 风险登记册（15 项）
  - 成本估算（首年 ¥326 万）
- **4 张关键示意图**：
  - 图 1：整体架构图（四层 + 三横向）
  - 图 2：视频播放时序图（14 个时序步骤）
  - 图 3：数据库 ER 图（11 张表）
  - 图 4：数据流图（DFD Level 0）
- **Docker Compose 开发环境**：
  - 9 个服务（MySQL 8.0 / Redis 7.2 / MinIO / RabbitMQ / Vault / ES 8 / Prometheus / Grafana / Prism Mock）
  - 健康检查 + 数据卷持久化 + 网络隔离
  - 一键启动所有依赖
- **Flyway 数据库迁移**：
  - V20260812_1000__init_schema.sql（26 张表 + 完整 FK 约束）
  - V20260812_2000__seed_baseline.sql（4 角色 17 权限 + 管理员种子）
- **Kubernetes Helm Chart**：
  - Chart.yaml + values.yaml + 9 个 templates
  - Deployment/Service/Ingress/ConfigMap/Secret/PDB/HPA/ServiceMonitor
  - Pod Security Context（runAsNonRoot + readOnlyRootFilesystem）
- **CI/CD 流水线**：
  - GitHub Actions：7 阶段（lint → db-migration → backend → worker → frontend → docker → security）
  - GitLab CI：9 阶段（含 staging/prod 部署）
  - 镜像构建 + Helm Chart 打包 + Release
- **API Mock**：
  - Prism 动态 Mock Server（dev/mock/prism.sh）
  - Postman Collection 集合（4 大类 9 端点）
- **7 篇架构决策记录（ADR）**：
  - 0001 DLP 自研 vs 采购
  - 0002 Spring Boot 3.x 选型
  - 0003 JWT 鉴权方案
  - 0004 视频加密方案
  - 0005 终端透明加密（开源自研）
  - 0006 国密 + 国际双轨
  - 0007 Helm K8s 部署
- **OpenAPI 3.0 规范**（v3.0）：30 端点 / 26 Schema / 10 Tags
- **数据库 Schema 文档**（v3.0）：17 张表详细定义
- **可观测性**：
  - Prometheus 配置（7 个 scrape job）
  - Grafana 仪表板 JSON
  - Alertmanager 路由配置
- **运维 SOP**：
  - 01 故障应急响应
  - 02 灾备演练（RTO 30min / RPO 5min）
  - 03 性能调优
  - 04 变更管理

### 修复
- v1.0 / v2.0 评审识别的 16 项问题全部修复：
  - P0 5 项（MD5 矛盾/移动端/驱动失效/合规拒签/工期）
  - P1 7 项（批量/搜索/标签/通知/备份/鉴权/时区）
  - P2 4 项（术语/视觉资产/缓存/文档化）

### 变更
- 从 v2.0 评审修订版（30 pages）扩展到 v3.0 终稿（762 KB）
- 从"评审修订版"升级为"完整方案"

---

## [2.0.0] - 2026-08-12

### 新增
- 评审修订版技术方案书
  - P0 修复 5 项：HLS≠DRM 定位/移动端策略/驱动失效兜底/合规拒签/工期
  - P1 修复 7 项：批量操作/搜索/标签/通知/备份/鉴权/时区
  - P2 修复 4 项：术语统一/视觉资产/缓存策略/文档化
- 19 张表格 + 7 张关键示意图
- 风险登记册 15 项
- 成本估算（首年 ¥326 万）

---

## [1.0.0] - 2026-08-12

### 新增
- 初版技术方案书
- 4 大核心模块：
  - 动态 Token 鉴权 + Secure Link
  - HLS 视频切片 + AES 加密
  - 终端透明加密（开源自研）
  - 频域盲水印 / 视频帧指纹
- 2 阶段审核（部门 + 法务 + 阅后即焚）
- 30+ 章节，12 万字

### 局限（已通过 v2.0/v3.0 修复）
- HLS+AES-128 被误定位为 DRM
- 驱动层工作量被严重低估（仅一句话带过）
- 合规风险（个保法/等保/国密）完全未涉及
- 工期低估（11-16 周，实际 35-49 周）
- 缺乏威胁建模、ADR、SOP、CI/CD、Helm Chart
- 视觉资产（架构图/时序图/ER图）缺失

---

## 版本对比

| 版本 | 发布日期 | 状态 | 主要改进 |
| --- | --- | --- | --- |
| [3.3.0] | 2026-08-28 | ✅ 当前 | 合规 + 安全 + 性能 11 项全部闭环（等保 P0 + 商密材料 + 国密 + SSO + WebAuthn + 频域水印生产 + 数据分级 + ES CDC + SDK v1.1 + ArgoCD + 压测）|
| [3.2.0] | 2026-08-12 | ✅ 历史 | 10 项改进（Controller 100% + Flyway + 限流 + cursor + 软删除 + Webhook + ES + sys_session + 移动 SDK v1.0 + CI 对齐）|
| [3.1.0] | 2026-08-12 | ✅ 历史 | API + DB + drawio 全面扩展 |
| [3.0.0] | 2026-08-12 | ✅ 历史 | 完整方案书 + 全部 DevOps 基础设施 |
| [2.0.0] | 2026-08-12 | ✅ 历史 | 评审修订版（16 项问题修复）|
| [1.0.0] | 2026-08-12 | ✅ 历史 | 初版方案书 |

## 提交类型说明

- **新增** (Added)：新功能
- **变更** (Changed)：已有功能的变更
- **废弃** (Deprecated)：即将移除的功能
- **移除** (Removed)：已移除的功能
- **修复** (Fixed)：Bug 修复
- **安全** (Security)：安全相关变更

## 版本号规则

- **MAJOR**（主版本）：不兼容的 API 变更
- **MINOR**（次版本）：向后兼容的功能新增
- **PATCH**（修订号）：向后兼容的 Bug 修复

预发布版本：`-alpha.1` / `-beta.1` / `-rc.1`

## 链接

- [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)
- [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)
- [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)
- [项目主页](README.md)
- [架构文档](ARCHITECTURE.md)