# 更新日志（Changelog）

本项目所有重要变更记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/) 规范。

## [未发布]

### 计划中
- v3.4 中期（W5-W18）：等保测评现场执行 + 商密许可颁发 + 频域水印生产化灰度
- AI 辅助文档审核（敏感信息自动识别 + LLM 二次校验）

### 修复

#### 越权转授 —— 拿着 distribute 位就能发 download 位

**这是仓库里最严重的一个权限缺陷**（第三轮代码审计 §1，critical）。

`canDistribute()` 只检查一个条件：**持有 distribute 位**。
而 `grant()` 对**要授出去的位掩码**只校验"是不是合法位"，
**完全不校验授权人自己有没有这些位**。

攻击路径：上传者 A 给同事 B 授权 `view|distribute`（17）——
这是"允许 B 转发"的常规操作。B 拿着这 17 去调
`POST /documents/batch-permission`，body 写 `actions=63`（= `ACTION_ALL`）。
后端放行（B 有 distribute 位）、写库（63 是合法位）⇒
**C 拿到了这份 L3 文档的下载、编辑、删除、打印权** ——
而 A 从头到尾只给过 B "看 + 转发"。

**B 的权限被放大了 3.7 倍。** 视频侧完全同构。

| 位 | 后果 |
| --- | --- |
| download | 拿到原文 —— L3 文档原文出了系统就是一次实打实的泄密事件 |
| delete | 销毁证据 —— 审计链上留下的是"C 删的" |
| distribute | 传播链可以拉长（`ACTION_ALL` 里有这一位） |

方案书 §5.4「越权分享自动阻断」要防的就是这件事 ——
`DocPermissionService` 自己的注释里也写着"不能拿着别人的授权去扩大授权范围"，
**但代码只挡住了"完全没有 distribute 位的人"**。

整改：

- **规则**：要授出的权限不得超过授权人自己持有的权限。两个维度都卡：
  - **位掩码**：`actions & ~own_bits == 0`
  - **有效期**：`expire_at` 不得晚于授权人自己那份额权的到期时间
    （这一条报告没提，但它是同一个漏洞的另一个面 ——
    授权人自己那份 30 天后到期，却能授出"永不过期"）
- `own_bits` = 显式授权位 ∪ **密级兜底位**（L1/L2 有 view、L1 还有 download）
  —— 不算上的话会出现"能转发却不能转授 view"这种自相矛盾
- **例外**：管理员与上传者本人不受限（上传者对自己上传的文档天然全权）
- 规则落在**写入点**（`grant()`），不是 Controller ——
  Controller 已经查过 `canDistribute`，但规则要保证将来多一个调用方也不漏
- 新增 `security/GrantScope`，文档侧与视频侧**共用一份**（不各写一遍）
- 新增 `PrivilegeEscalationTest`（11 条，服务层）
  与 `PrivilegeEscalationEndpointTest`（2 条，**端点层接真实 service**）
  —— 后者是因为原有的 `DocPermissionControllerTest` 把 service mock 掉了，
  **根本测不到这个漏洞**
- 验证：摘掉校验后 6 条变红，端点从 403 变 **200**（越权真的成功了）

顺带修：前端 403 提示原本统一成"没有权限执行该操作"，
把后端可操作的 detail（"不能授出自己没有的权限：下载、打印"）丢掉了；
ShareDialog 同一条消息弹两遍。

#### 5 个 DB 校验脚本只挂在死掉的 GitLab 流水线上

**端口一致性校验、模型↔表列对账、迁移合法性校验、FK 类型校验、MySQL 端口校验
在真正会跑的 CI 里全部缺席**（第三轮代码审计 §5）。

这 5 个脚本此前只在 `.gitlab-ci.yml` 里，而本仓库的 remote 是 github.com
⇒ 那条流水线**根本不会跑**。CLAUDE.md 写着"仓库里有端口一致性校验"，
但那个校验实际上没在跑。

⇒ 全部接进 `.github/workflows/ci.yml` 的 `db-migration` job，**放在迁移之前**
（静态校验不需要真库，能提前抓到 "Unknown column" 与 "Error 3780 are incompatible"）。

顺带修：

- **Docker 后端镜像的 HEALTHCHECK 探错端口**（§2，高）——
  探的是 8080，而后端实际监听 **8092** ⇒ 直接 `docker run` 后端镜像时
  healthcheck 永远失败 ⇒ 容器被标 unhealthy ⇒
  `depends_on: service_healthy` 的编排永远等下去。
  K8s 生产没事（探针走 helm 的 deployment.yaml，不看 Dockerfile）。
- **端口校验脚本看不见 Dockerfile** ⇒ 这正是上面那个漂移能活到现在的原因
  ⇒ 把 Dockerfile 的 HEALTHCHECK 与 EXPOSE 加进来源列表（9 处 → 11 处）。
- **表数量阈值 20 → 35**（README / CLAUDE.md / database_schema.md 都承诺 35 张表）
- **这 5 个脚本在 Windows 上跑不起来** —— 打印 ✅/❌ 时抛 `UnicodeEncodeError`，
  **而且是在校验通过之后**（最后那行 print 崩掉）⇒ "校验通过"被报成"脚本报错"

#### 前端把凭据打进浏览器控制台

**每一个出错的前端请求，都会把登录 JWT 打进控制台**（第三轮代码审计 §6/§7 的延伸）。

根因：axios 的错误对象里有 `config.headers`，而前端正是在那里塞登录 JWT
（`api/client.ts:55`）⇒ `console.error('...', e)` 把整个错误对象打出去，
**JWT 就在里面**。实测确认：

```
JSON.stringify(error) 里出现 SECRET 了吗： true
error.config.headers.Authorization = Bearer SUPER_SECRET_LOGIN_JWT
```

前端有 **11 处**直接打错误对象。

另有两处报告已记的：

- `VideoDetail.vue` 打 m3u8 URL —— 里面带着 `?token=<播放 JWT>`
  （10 分钟有效，可换取该视频的 m3u8 / 分片 / **AES 密钥**）
- hls.js 的 key-loader 把**完整的密钥 URL** 嵌进了错误消息
  （`new Error("Invalid key URI: \"" + uri + "\"")`）
  ⇒ `data.error.message` 里带着可用的播放凭据，
  而它会流进控制台**和**用户看到的错误文案 ⇒ 两处都泄

修复：

- 新增 `web/src/utils/safeLog.ts`：`safeError()` 只挑排障需要的字段
  （状态码 / 方法 / URL / detail），**整个 headers、request、response 都不进摘要**
- 11 处全部改用 `safeError(e)`
- m3u8 URL 用已有的 `redactTokenFromUrl()` 打码
- HLS 事件日志的签名从 `log(e, d?)` 改成 `log(e)` ——
  **在类型层面就不给传对象的机会**（事件对象里带着分片/密钥 URL）
- 新增 `redactTokenFromText()`：对**任意文本**打码
  （hls.js 的消息里 token 不在 `?`/`&` 之后，原来的正则漏掉了）

新增 `safeLog.spec.ts`（12 条）+ 扩充 `hlsError.spec.ts`（+5 条）。

#### CI 里三个 job 引用不存在的文件或跑在错的目录

第三轮代码审计 §4。逐条核对过 action 定义后修的：

| job | 原问题 | 修法 |
| --- | --- | --- |
| SonarQube | 在**没有 pom.xml** 的仓库根目录跑 `mvn` ⇒ 必然失败 | 加 `working-directory: backend` + 新建 `backend/sonar-project.properties` |
| Snyk ×3 | docker action 在根目录跑 `snyk` —— 根目录没有 pom/package/requirements ⇒ **扫了空气**（这三个 action 都**没有** `working-directory` 输入，已核对 action.yml） | 用 `args` 的 `--file=` 指定清单文件 |
| ZAP | 扫 `localhost:8092` 但**没起任何服务**；且 `.zap/rules.tsv` **不存在** | 起 `dev/mock/server.py`（4010 端口，已验证 `/health/live` 返回 200）+ 去掉不存在的 rules 文件 |
| Trivy | 扫 `openjdk:17-jdk-slim` —— **这个镜像仓库里根本不用** | 矩阵扫实际会发布的三个基底镜像（temurin / python-slim / nginx-alpine） |
| SBOM | `cyclonedx/cyclonedx-maven-plugin@v2.7.9` —— 该仓库是 **Maven 插件，没有 action.yml**（已核对），tag 也不对 | 改成在 `backend/` 里跑 `mvn cyclonedx:makeBom`；Python 侧改用 `cyclonedx-py requirements`（worker 无 setup.py，`-e .` 跑不起来） |
| 覆盖率 | `grep -oP`（PCRE）在部分 runner 上不可用；阈值 95 与**承诺的 100%** 不一致 | 改用 Python 解析；阈值提到 100 |

另修第三轮 §9：**secret 没配时 job 直接失败**（不是跳过）⇒ workflow 长期红着
⇒ 没人去修 ⇒ 安全扫描等于没有。⇒ 依赖 secret 的 job 加
`if: secrets.X != ''`（配了就跑，没配就**跳过**）。

#### MyBatis-Plus 分页插件从未注册 —— 10 个列表端点返回全表

分页插件**从未被注册过**。MyBatis-Plus 的 `selectPage(IPage, Wrapper)`
本身不做 LIMIT —— LIMIT 是 `PaginationInnerInterceptor` 在 SQL 拦截层
改写出来的。而 MP 的自动装配带 `@ConditionalOnBean(InnerInterceptor.class)`，
**应用不自己声明 bean，它就不会被创建**。

后果：10 个列表端点（`/videos` `/documents` `/users` `/audit` `/notifications`
`/webhooks` `/distributions` `/v2/videos` …）返回**全表**，
`page_size` 参数完全失效，`total` 恒为 0 ⇒ 前端分页器一直显示"共 0 条"。

最隐蔽的一处是审计导出：5 万行的上限保护形同虚设 ——
因为 LIMIT 没生效，代码是**先把整张 `operation_log` 拉进内存**再检查行数
（方案书 §14.3 资源耗尽型）。

整改：

- 新增 `config/MybatisPlusConfig` 注册分页插件（`maxLimit=1000` 兜底，
  `overflow=false` ⇒ 页码越界返回空而不是回退首页）
- 新增 `util/PageParams` 夹住 `page` / `page_size`（1~100），9 处调用点全部改用
- **审计导出显式 `page.setMaxLimit(50001)`** —— 全局 `maxLimit=1000`
  会把它静默截断成 1000 行，"5 万行上限"就变成了"最多导出 1000 行而且不报错"
- 顺带修掉 Webhook 投递历史的 `size = 20 * page` 公式
  （每页条数随页码线性增长，分页真生效后第 21~40 条会被整段跳过）
- 新增 `MybatisPlusConfigTest`（10 条，H2，**用生产配置类**），
  并验证过"摘掉插件测试必红"

#### iOS SDK 根本编译不过 —— 且与后端的解码契约对不上

`mobile-sdk/ios/` 有两处**必然编译失败**的语法错误
（`avPlayerLayer = = nil`；`setupPlayer` 用了不在作用域的 `accessToken`），
`Package.swift` 还指向不存在的 `EDAMPlayerTests/` 目录 ⇒ 整个包构建不了。
而 **CI 里没有任何 mobile-sdk 编译检查** —— 这是它能长期潜伏的原因。

即使编译过了也跑不起来：

- `PlaybackSession` 声明了 `userId`（后端**根本不返回** `user_id`）和
  `expiresAt: Date`（后端返回的是 ISO-8601 **字符串**）⇒ **每次播放都解码失败**
- 水印用的是 `currentSession?.userId` ⇒ **永远显示 "USER_0"**，
  水印看着在，其实指不出是谁
- 播放日志只 `print` 一行 ⇒ **移动端在溯源链上是空白**
  （前端早就接了 `/playback/{id}/log`，`play_log` 表保留 365 天）
- `m3u8_url` 是**根相对**路径，直接 `URL(string:)` 得到的 URL 没有 scheme
  ⇒ AVURLAsset 拉不到内容
- 两个资源泄漏：播放进度 observer 句柄没存（AVPlayer 永远不释放）、
  `AVPlayerLayer` 没从视图上移除

整改：

- 改用后端签发的**播放 token**（`scope=playback` + 绑定 video_id），
  **不再往 AVURLAsset 塞登录 JWT** —— 那三个端点是 `permitAll`，
  只读 query token；而且 `AVURLAssetHTTPHeaderFieldsKey` 是 Apple 未公开的私有 API
- 解码契约与后端对齐（显式 `CodingKeys` + 自己做 ISO-8601 解析），
  后端加 2 条**契约测试**把字段名钉死
- 水印改用工号（`/auth/me` 的 `employee_no`），与前端一致
- 播放日志接上 `/playback/{id}/log`
- 修掉两个资源泄漏 + 根相对 URL 拼接
- **CI 新增 `mobile-sdk-ios` 编译门**（macos runner）——
  没有这一步，上面这些改完照样会烂掉

顺带修：Android 灰度哈希负 bucket（`%` 保留符号 ⇒ 负数恒命中，
10% 实际命中 **55.4%**，实测 10000 个工号命中 5543 个）；
iOS `abs(Int.min)` 崩溃（Swift 里是 precondition 失败）；
iOS 文档预览缓存命中分支把响应体当 URL 解析。

`mobile-sdk/README.md` 的能力表此前把**全部能力都标成 ✅**，
但 Android 的播放器与水印**根本没实现** ⇒ 已按代码实际情况重标。

#### SVG 文档绕过白名单 —— 存储型 XSS，落在持有 JWT 的认证域上

上传白名单用 `c.contains("image/")` 匹配 MIME ⇒ `image/svg+xml` 正好命中。
SVG 不是图片，是**可执行 XML**，浏览器会内联解析并执行其中的 `<script>`。

攻击路径：上传带脚本的 SVG → 分享给同事 → 对方打开预览
⇒ 脚本在**后端域名**下执行 ⇒ 直接偷走 localStorage 里的
`access_token` / `refresh_token`。

`X-Content-Type-Options: nosniff` **挡不住** ——
它只防"声明类型与实际内容不符"，而 SVG 的声明类型**就是**
浏览器会执行脚本的类型。

整改（后端三处 + 前端三处）：

- 上传白名单排除 SVG（**后缀检查放在 MIME 分支之前** ——
  MIME 分支命中就 `return true`，后缀分支根本没机会执行，
  `image/png` + `payload.svg` 会混进来），并排除 `text/*` 与 `.html`
- 落库 MIME 改由**服务端按后缀判定**，不信客户端的 `Content-Type`
- 预览 inline 白名单改成**精确枚举**（`image/svg+xml` / `text/html` 一律不 inline）
- 前端同样收紧（**关键**：前端走 `fetch → blob → iframe`，
  **完全绕过服务端的 `Content-Disposition`**；
  且 Blob URL 的 origin 继承本站 ⇒ SVG 脚本就在本站 origin 下执行）

新增 `DocumentSvgXssTest`（9 条）+ `previewMime.spec.ts`（12 条）。

#### 视频转码被 RabbitMQ 掐断 —— 失败在监控上完全隐形

2026-11-10 线上事故：video 14 转码 12.7 分钟成功，video 15 转码到
**正好 30 分钟**就被掐断。根因是 RabbitMQ 的未确认投递超时
（`consumer_timeout`，默认 1800000 ms = 30 分钟）到点掐断 channel，
正在跑的 consumer 协程被连带取消。

三个连锁缺陷：

| 缺陷 | 后果 |
|---|---|
| `asyncio.CancelledError` 继承 **BaseException**，`except Exception` 抓不到 | 不打失败日志、不进 Prometheus、`/health/worker` 不更新 ⇒ **失败在监控上隐形** |
| `message.process()` 的 `__aexit__` 在死 channel 上抛 `ChannelInvalidStateError` | 顶替掉真正的失败原因 ⇒ 日志里只剩一句没头没尾的 "channel invalid state" |
| 转码被取消时 FFmpeg 子进程没被杀 | 变成吃 CPU 的孤儿进程，而 worker 侧看不到任何异常 |

顺带查出 `VideoTranscodeFailure` 告警是**条死规则** ——
它查 `result="failed"`，而代码发出的值是 `failed_permanent`，
**从来没触发过**（这次事故正是被它漏掉的）。

整改：

- **broker 侧**：`dev/rabbitmq/rabbitmq.conf`（新增）+ `dev/docker-compose.yml`
  调大 `consumer_timeout`；`ops/rabbitmq/README.md`（新增）给出**按 RabbitMQ
  版本分别的**生产环境操作手册（生产 broker 不在本仓库的 Helm chart 里）
- **worker 侧**：新增任务墙钟上限 `WORKER_TASK_DEADLINE_SEC`（3 小时，
  跨重试共享的**整条消息**预算）—— 超时由 worker 主动触发，
  拿到干净可诊断的失败，而不是被 broker 从背后掐断
- **worker 侧**：显式处理 `CancelledError`；区分"自己优雅关闭"与
  "broker 掐断"（前者不记失败，后者必须记）
- **worker 侧**：新增 `kill_process_nowait()` —— 取消中的协程不能 `await`，
  FFmpeg / LibreOffice 不再留孤儿进程
- **监控**：修掉死规则的 label 值；新增 `WorkerTaskCancelledByBroker` 告警

详见 `modify/2026-11-10-视频转码被broker掐断-consumer-timeout.md`。

---

## [3.4.2] - 2026-11-08

> v3.4.1 把「写好但没接线」的功能接上了线；这一版是**回头审一遍已上线代码**，
> 结果挖出四个比那八项更严重的问题 —— 特征都是：
> **接口返回成功、界面弹"成功"、但底层什么都没发生**，或者
> **前端按权限过滤了菜单、后端却没设门**。

### 修复

#### 一批端点漏了 `@PreAuthorize`，任何登录用户都能直接调

前端菜单早就按权限过滤了（「审计日志」要 `audit:read`），
但后端对应端点**没有门** —— 绕过菜单直接调 API 就行：

| 端点 | 漏掉的后果 | 补的门 |
|---|---|---|
| `GET /audit/logs*` | **全公司操作审计日志**（谁在哪个 IP 预览了哪个 L4 文档） | `audit:read` |
| `GET /users`、`GET /users/{id}` | 全公司员工名册（工号 / 邮箱 / 部门 / MFA 开关 / 登录失败次数 / 最后登录 IP） | `user:read` |
| `GET /admin/backups` | 备份清单（今天返回空列表，是个等着被填上的洞） | `admin:backup` |
| `GET /rbac/users/{id}/permissions` | 任意用户的有效权限清单 | `user:read or role:read` |

- 这四个权限码**本来就在权限目录里**，`dept_manager` / `auditor` 也早就授权了 ——
  只是端点忘了写 `@PreAuthorize`。补门不影响既有角色。
- 新增 `EndpointAuthorizationTest`：扫全部 `@RestController`，
  每个端点要么有门、要么在**例外清单**里写明"这里靠什么鉴权"。
  以后再加端点忘了设门 ⇒ 测试红。例外清单同时校验"没有僵尸条目"。

#### 「紧急吊销」是个空实现

`POST /users/{id}/revoke-keys` 原先只打了一条日志就返回 204 ——
**token 一个都没作废**。管理员在界面点「紧急吊销」，弹成功提示，然后什么都没发生。
`sys_session.revoked` 字段、`SysSessionRepository.revokeAllByUserId` 全是死代码。

- Redis 维护「用户 → 其 refresh_token 集合」索引（原先只有 `refresh:<token>` → userId，
  **没法按用户反查**，只有 `KEYS refresh:*` 全库扫描这种下策）
- 吊销 = 取出索引里的 token 逐个作废 + `sys_session.revoked = 1` 留痕
- 前端补上「紧急吊销」按钮（原先**根本不存在**，只有手册里有）
- 口径写准：**refresh_token 立即作废（续期通道当场断）**；
  已签发的 access_token 是无状态 JWT、无服务端黑名单，最多再活一个 TTL（10 分钟）

#### refresh 不查账号状态、refresh_token 不轮换

- 管理员把离职员工禁用（status=2）之后，他手上的 refresh_token 照样能换出
  新的 access_token —— 「禁用」只是个页面上的标记，管不住人。
  现在 `/auth/refresh` 也走 `assertLoginAllowed`。
- refresh_token 原封不动继续用，一张泄露的能在整个 7 天 TTL 里无限续期，
  **没有失效点、没有重放检测**。现在**每次刷新轮换**：旧的当场作废，
  响应里回一张新的（前端 `client.ts` 已兼容）。

#### 外发审批模块

| 问题 | 现象 | 修法 |
|---|---|---|
| 请求 DTO 缺 `@JsonProperty` | 前端发 `doc_id` / `external_recipient` / `valid_hours` **全绑不上** ⇒ 「发起外发审批」在界面上从来就没成功过（恒 400） | 逐个标注 snake_case |
| 入参不校验 | 缺 `external_recipient` ⇒ NPE → **500**；缺 `reason` / `valid_hours` ⇒ 撞 NOT NULL → **500** | 统一入口校验，非法一律 **400** |
| 审批意见不保存 | `comment` 收了却从不写；`distribution_approval_decision` 表**零引用** ⇒ 谁批的、什么时候、批语是什么**全都查不到** | 写决策流水表，新增 `GET /distribution/approvals/{id}/decisions` |
| 没有状态机 | 已驳回的申请能被再"批准"一次 | 只允许 `pending` 决策，否则 **409** |
| 可以自审自批 | 有 `distribution:approve` 的人能审批**自己**提交的申请 | 申请人 ≠ 审批人，否则 **403** |
| 驳回可以不写理由 | 事后追责只剩"被拒了"三个字 | 驳回时 `comment` 为空 ⇒ **400** |

#### SSO 登录：部门被每次登录重置成「总公司」（会改小密级基线）

`SsoUserProvisioning.resolveDeptId()` 忽略了自己的入参、**恒返回 1L**（总公司），
而 `updateExistingUser()` 无条件用它覆盖用户部门 ⇒ **每登一次 SSO，
用户的部门就被重置成「总公司」**。

后果不止显示难看：密级基线是按**部门名**判定的（法务 / 审计 / 人力 → L3），
「总公司」一个都不命中 ⇒ 基线掉到 L1；而最终密级 = max(服务端基线, 客户端声明)
⇒ **一个法务部的员工用 SSO 登一次，之后就能把资产声明成 L1 公开**。
L3「同部门可见」的范围也一并变了。

现在按名字精确查 `sys_dept`，**查不到就保持原部门不动**并告警；
兜底部门才走 `edam.sso.default-dept-code`（这个配置此前根本没被用过）。

#### SSO 登录：IdP 没映射工号 ⇒ 500

`provision()` 拿 null 工号去查库 → 查不到 → 走新建分支 → 往 NOT NULL 列插 null → **500**，
错误信息完全指不到根因。现在入口就拦，给出"请检查 SSO 的属性映射配置"。

#### SSO 登录：OIDC 侧两个 HTTP 问题

- `HttpClient` **没设请求超时** ⇒ IdP 挂住时 SSO 回调线程被一直占着（两个请求都加 10 秒超时）
- `fetchUserInfo` **不看状态码**就拿 body 当 userinfo ⇒ IdP 返 401/500 时
  所有属性解析成 null ⇒ 拿 null 工号去建账号

#### 审计导出：整个是假接口

`POST /audit/logs/export` 返回的是一个指向
`https://api.example.com/api/v1/audit/exports/...` 的**伪造下载链接** ——
那个路由根本不存在，也没有何导出任务机制，但接口返回 202 + 一个很像样的链接。
前端没有导出按钮，手册却写着"生成 Excel 文件，MFA 二次校验"。

现在**同步返回真的 CSV**（UTF-8 BOM，Excel 直接能开）：
过滤口径与列表完全一致、单次上限 5 万行、**MFA 二次校验**、
`Cache-Control: no-store`、**CSV 公式注入防护**
（detail 是用户可控的，以 `= + - @` 开头的单元格会被 Excel 当公式跑）。

#### RBAC：分配权限的接口能改系统角色

`updateRole` / `deleteRole` 都挡了 `is_system=true`，**只有 `assignPermissions` 没挡** ——
"编辑角色"页面点不动 `employee`，但分配权限的接口能把它的权限集整个换掉。
三个入口现在口径一致。另补权限 id 存在性校验（原先传错 id ⇒ 撞外键 ⇒ 500）。

#### 国密：exp 缺失时 fail-open

`parseToken` 里 `if (exp instanceof Number ...)` —— **exp 缺失时整段被跳过**，
等于"没有过期时间的 token 也认"。现在缺 exp 直接拒。
另修一条复制粘贴留下的错误信息（"edam.worker.token 需一致"跟 JWT 无关），
并移除 `SM3Util` 上已经过时的 `@Deprecated`（它现在**确实**被调用）。

#### Webhook：SSRF 守卫的四个缺口 + 响应体无限读

- IPv6 只查了 `::1` ⇒ `[fc00::1]` / `[fe80::1]` / `[::ffff:169.254.169.254]` 能过
- **不解析主机名** ⇒ `http://metadata.internal/`（解析到 10.x）能过
- 十进制 / 十六进制 IP ⇒ `http://2130706433/`（= 127.0.0.1）能过
- 响应体用 `BodyHandlers.ofString()` **整个读进内存**，"截断到 1KB"是读完之后才做的
  ⇒ 返回 1GB body 的回调目标就能把后端 OOM 掉

现在抽出 `SsrfGuard`（协议白名单 → IPv4/IPv6/数字写法私网判定 → **解析主机名逐个判**），
**投递前再查一次**（fail-closed），`HttpClient` 显式不跟随重定向，响应体改成流式限量读 64KB。

#### Worker：FFmpeg / ffprobe / LibreOffice 三处子进程没有超时

三处都是 `await proc.communicate()` 裸等。一个 pathological 的输入
（损坏的视频、畸形 docx、半截网络流）就能让协程**永远不返回**；
而 `prefetch_count=10` ⇒ **10 个卡死的任务就把整个 worker 堵死**：
队列不再消费、CPU 被占满，**没有异常、没有日志**
（进程还"活着"，`/health/worker` 照样返回 ok，运维看不出来）。

新增 `edam_worker/procutil.py`（`kill_process` / `run_with_timeout`），
三处全部改用带超时的执行，超时后**杀掉子进程**
（先 `terminate()`，给 5 秒宽限期，不行就 `kill()` —— 只 terminate 不够，
挂在 I/O 上的进程收不到 SIGTERM，会继续吃 CPU）。
超时配置：`FFMPEG_TIMEOUT_SEC`（3600）/ `FFPROBE_TIMEOUT_SEC`（120）/
`LIBREOFFICE_TIMEOUT_SEC`（600）。

#### 模型 ↔ 数据库列 对账：Webhook 投递历史看不出重试

写脚本把 24 个 Java model 的每个字段与 35 张迁移建出来的表逐列对账
（处理 `ADD COLUMN` / `CHANGE COLUMN` / `DROP COLUMN`）：**0 处不匹配**。
但查出 **7 列"库里有、model 里没有"**，其中一处是真缺口：

- `webhook_delivery.retry_count` / `next_retry_at` —— 表里从建表起就有，
  `WebhookDelivery` model **一直没映射** ⇒
  `retry_count` 恒为 0（"失败了 3 次然后成功"和"一次就成功"在列表里长得一模一样）、
  `next_retry_at` 恒为 NULL（**看不出"这条还在等重试"**）。
  现在 model 补上两个字段，投递时写 `attempt`、调度重试时回写下次重试时间。

另两列已在 `doc/database_schema.md` 写明是**未接线的历史遗留**：

- `sys_user.default_classification`（注释写着"用户上传默认密级（按部门）"）——
  Java 侧**零引用**，管理员改了它**不生效**。
  实际生效的是上传时 `ClassificationEnforcement` 现算的基线（按**上传者所在部门**），
  也就是说"按部门定密级"这条规则**已经实现了**，只是不走这一列。
- `sys_user.pwd_history` —— 真正的密码历史在独立的 `password_history` 表。

### 文档（按代码为准，改掉手册里的失真描述）
- **管理员手册 §4 外发链接**：手册写着"同意 → 颁发外发链接"并给出
  `https://EDAM/external/token?doc_id=N` —— **那个端点从来不存在**，
  没有 `/external/*` 路由，`external_doc_view_log` 表也没有任何写入方。
  审批通过后**不会**产生外部接收方能用的链接。已按代码改写。
- **管理员手册 §3.3 权限目录**：46 条权限码里只有 **25 条**真正挂在端点上，
  另 **21 条**没有任何端点读取（含 `mfa:manage` / `system:manage` /
  `leak:manage` / `sso:manage` / `document:download` / `document:print` 等）。
  在「角色权限」页勾它们**不会**让角色多出对应能力 —— 手册已写明。
- **管理员手册 §9 系统配置**：手册说"修改后点保存 → 重启后端生效"，
  实际上**没有配置持久化端点**（前端点保存会明确提示要走配置文件）。已改。
- **管理员手册 §8.2 紧急吊销**："立即失效"改成准确口径（见上）。

### 工具

- `scripts/sync_openapi_permissions.py` 此前 `--write` 会给已有权限行的 operation
  **再插一行**，产生重复 YAML key（解析器只认最后一个，规范文件不可审阅）；
  且只认方法级 `@PreAuthorize`、只取多权限表达式里的**第一个** ——
  类级门被整段漏掉，多权限端点的契约被悄悄削弱。现已全部修正。
- 新增 `distribution_approval_decision` 的 Java 侧 model / repository。

### 变更

- 端点数 98 → **99**（新增 1 个决策流水查询端点）
- 有角色门的端点 48 → **57**
- OpenAPI 77 → **78** paths
- 版本 3.4.1 → **3.4.2**（7 处版本号同步）

### 验证

- 后端 219 → **261** 用例，0 失败（6 个 error 仍是既有的 `ApplicationStartupTest`，
  需要真实 MySQL，与本次无关）
- 新增测试：`SsoUserProvisioningTest`（6）、`RbacControllerTest`（3）、
  `AuditServiceExportTest`（5）、`AuditAspectTest` detail 模板求值（2）
- 前端 58 → **60** 用例全绿（新增 `client.spec.ts` 上传超时 2 例）
- Worker 99 → **103** 通过 / 2 跳过（新增 `test_subprocess_timeout.py` 4 例）
- 端点对账 98 ↔ 98 → **99 ↔ 99 / 100%**
- OpenAPI 78 paths / 99 ops / 40 schemas / 18 tags，全部 `$ref` 可解析、无重复 key

### 破坏性变更（上线前务必确认）

- `POST /audit/logs/export` 的响应形态变了：
  原 `202 + {task_id, download_url}` → 现 `200 + CSV 文件字节`。
  （原实现返回的 URL 本来就打不开，但如果有第三方在调，形状变了。）
- Webhook 的 SSRF 校验变严：原先能注册的地址（IPv6 私网、解析到内网的主机名）
  现在会被拒；已注册的这类地址会在**每次投递前**失败并记 `webhook_post_failed`。
- `/auth/refresh` 的响应多了一个 `refresh_token` 字段（轮换）—— 见上一轮说明。

#### 视频上传报错：三个缺陷

用户反馈「登录后上传视频报错」。提供的后端日志里**没有上传请求的记录**
（审计写的是 DB 表不是日志文件，日志又是筛过的片段），
所以这次是「代码层可证」的三处：

| 缺陷 | 根因 | 后果 |
|---|---|---|
| **前端上传被 30s 掐断** | `api.upload()` 没覆盖 `timeout`，axios 的 `mergeConfig` 让实例级的 30000 泄进上传请求 | axios 的 XHR `timeout` 是**总时长**（从 `send()` 算起）不是「空闲多久」⇒ **任何传超 30 秒的视频都被浏览器单方面掐断**，哪怕每秒都在收数据。代码里那套「5 分钟无进展」的 AbortController 根本没机会生效。是 `265abbd`（2GB→8GB）引入的回归 |
| **审计 detail 全为空** | `detail` 被整串丢给 `parser.parseExpression()`，而 SpEL 把 `=` 当**赋值**运算符 | `EL1009E: cannot be set on null` / `EL1041E: ...there is still more data` ⇒ **全仓 40 多处 `@AuditOp(detail=...)` 的补充说明全部求值失败**（操作了哪个对象、什么密级、批量几条 —— 审计最该有的部分）。改成按 `k=v` 模板逐段求值 |
| **网关比后端先返回 504** | nginx 网关 `location /api/` 三个超时全走默认 60s | 后端收完 8GB 还要算 SHA-256 + 传 MinIO + 落库 + 发 MQ，远超 60s ⇒ 网关先 504，**后端还在闷头干活** |

- 顺带澄清：手设的 `Content-Type: multipart/form-data`（不带 boundary）**无害** ——
  axios 在浏览器里会删掉它、由浏览器自己补 boundary。**不要**去动它。
- 测试：后端 `AuditAspectTest` +2（回退修复即变红，已验证）；
  前端 `client.spec.ts` +2（同样验证过）。
- ⚠️ **线上跑的不是当前 HEAD 的版本**（日志里缺 `token_issued` 那行），
  上述三处修复需**重新构建后端 + 前端 + worker 镜像并部署**后才生效。

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