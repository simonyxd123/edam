# 审计日志全量覆盖 —— 从"记了几个点"到"所有操作都留痕"

- 日期：2026-11-04
- 触发：用户要求"完善审计日志部分，需要详细记录所有的操作，
  比如登录、查看列表、查看详情、增加、修改、删除、播放、预览等操作"
- 涉及方案书章节：**§7.1 核心数据表**（审计与溯源：play_log / operation_log）、
  **§7.3 数据治理**（操作日志保留 180 天、播放日志保留 365 天）、
  **§8 安全策略总览**（鉴权层）、
  **§14.1 资产识别**（审计日志属于个人/受控资产）、
  **§14.3 STRIDE**（Repudiation 否认 → 操作日志全审计）

---

## 一、动手前先算清：原来的审计到底覆盖了什么

盘点 95 个端点后，结论很扎心：

| 类别 | 改造前 | 数量 |
|---|---|---|
| 有审计记录 | 登录、登出、文档预览、播放取 token、角色增删改、用户增删改 | 16 处 |
| **完全没记录** | 列表查询、查看详情、上传、删除、批量删除、授权/撤权、标签增删、Webhook 增删、通知偏好、备份恢复、外发审批、**文档下载**、搜索、审计日志自身的查询与导出 | **79 个端点** |
| 三种结果 | `result` 列设计了 success/failure/denied，**实际只写过 success** | — |

三条要害：

1. **失败与拒绝一条不记**。手写的 `auditService.log(...)` 都在方法体里，
   方法一旦在权限校验、参数校验处抛异常，**整条操作消失**。
   而"越权访问未授权视频"恰恰是防泄密系统里最该被记录的一类。
2. **前后端操作类型清单对不上**。前端下拉写的是
   `['login','logout','view','download','upload','delete','permission_change','config_change']`，
   后端实际写进库的是 `create / update / detail / preview / grant_permission …` ——
   **两套名字几乎不重叠**，于是审计页的"操作类型"筛选框形同虚设
   （选 `upload` 查不到任何东西）。
3. **失败登录的审计从来没写进过库**。`operation_log.user_id` 是 `NOT NULL` 且无默认值，
   而登录失败时操作者身份未知、代码写的是 `null` ——
   MyBatis-Plus 默认 `NOT_NULL` 插入策略会直接跳过 null 字段，
   于是 INSERT 语句里根本没有这一列，MySQL 报"字段无默认值" ⇒ **整条记录写不进去**。

---

## 二、方案：注解 + AOP 切面（而不是继续手写 log 调用）

### 2.1 为什么是切面

| 做法 | 成功 | 失败 | 拒绝 | 结论 |
|---|---|---|---|---|
| 手写 `auditService.log(...)` | ✅ | ❌ | ❌ | 漏掉最该记的一类 |
| **切面包住整个方法** | ✅ | ✅ | ✅ | 三态齐全 |

切面把"记录审计"这件事从业务代码里彻底拿出来：
每个端点只声明**这是什么操作、操作的是什么东西**，
至于成功还是失败、耗时多少、IP 是哪个、状态码多少，由切面统一处理。

### 2.2 三层结构

```
backend/src/main/java/com/example/edam/audit/
├── AuditOp.java        注解：@AuditOp(op=…, resourceType=…, resourceId=…, detail=…)
├── AuditOpType.java    操作类型清单（含历史别名归并表）
├── AuditDetail.java    detail 拼装 + 敏感字段脱敏
├── AuditAspect.java    切面：成功/失败/拒绝三态落库
└── AuditAccessFilter.java  兜底过滤器：记录"没进控制器就被 401/403 挡下"的请求
```

`@AuditOp` 用法：

```java
@GetMapping("/{video_id}")
@AuditOp(op = AuditOpType.DETAIL, resourceType = "video", resourceId = "#videoId")
public ResponseEntity<?> getById(@PathVariable("video_id") Long videoId, …) { … }
```

- `resourceId` / `detail` 是 **SpEL**：`#videoId` 按参数名，`#p0` 按位置（不依赖编译参数名）。
- `detail` 里只放**不敏感**的字段（标题、密级、条数）；凭据类字段由 `AuditDetail` 自动脱敏。
- `hideParams = true`：整条请求参数摘要都不落库（登录、改密码这类）。
- `auditSuccess = false`：**只有失败与拒绝才留痕**（高频接口用）。

### 2.3 三个"不记就等于没记"的坑，逐个堵上

| 坑 | 症状 | 处理 |
|---|---|---|
| `@PreAuthorize` 的 403 发生在切面**之外** | 方法级授权拦截器是另一个 Advisor，顺序比普通 Advisor 高；切面排在它里面就够不着 | 切面标 `@Order(Ordered.HIGHEST_PRECEDENCE)`，排成**最外层**，把授权拦截也包进来 |
| JWT 校验失败发生在**过滤器链**里 | 请求压根到不了控制器方法，切面无从谈起 | 新增 `AuditAccessFilter`，注册在 Security 链**内部**、JWT 过滤器**之后**，专门记这一类（op=`access_denied`） |
| 接口用 `return ResponseEntity.status(FORBIDDEN)` 而不是抛异常 | 只看异常 ⇒ 被拒的访问被记成 "success" | 切面**同时看状态码**：403/401 ⇒ `denied`，其它 ≥400 ⇒ `failure` |

切面与过滤器用请求属性 `edam.audit.recorded` 协调，不会重复记账。

---

## 三、改动明细

### 3.1 数据库（Flyway `V20261104_1000__audit_log_full_coverage.sql`）

`operation_log` 加列 + 放宽约束：

| 变更 | 原因 |
|---|---|
| `user_id` 改 **NULL** | 登录失败时操作者未知（原来 NOT NULL ⇒ **失败登录的审计从来没写进过库**） |
| `+ http_method` | 区分读/写 |
| `+ endpoint` | 命中的**路由模板**（`/videos/{video_id}`），比真实路径更适合聚合 |
| `+ status_code` | 这次调用真实的 HTTP 状态码 |
| `+ duration_ms` | 服务端耗时 |
| `+ session_id` | 把一次登录串起来的多次操作 |
| `+ idx_endpoint` / `idx_resource_time` | 按端点查、按资源类型+时间查 |

`play_log` 表**不需要迁移** —— 它在 `V20260812_1000__init_schema.sql` 里已经建好了，
只是从来没有人往里写过（`POST /playback/{id}/log` 只打了一行 `log.info`）。

### 3.2 后端

- **新增** `audit/` 包（5 个类，见上）
- `AuditService`：新增 13 参 `log(...)` 重载，统一汇到私有 `write(...)`；
  查询侧支持 `result` / `resource_type` 过滤、按规范名**展开历史别名**再查
- `AsyncExecutorConfig`：新增 `auditExecutor` 线程池（core2/max4/queue2000，
  `CallerRunsPolicy`）—— 审计写入是高频的，不能和低频长耗时的 webhook 投递挤一个池
- `SecurityConfig`：注册 `AuditAccessFilter`（在 Security 链内部、JWT 过滤器之后）
- `OperationLog` / `AuditLogDTO`：补新字段
- `AuditController`：新增 `GET /audit/logs/operation-types`（前端下拉的数据源）、
  `GET /audit/logs/resource-types`（从库里 DISTINCT，不写死）；
  补 `result` / `resource_type` 过滤（前端**早就在传** `result`，后端却根本不支持 ⇒ 那个筛选是假的）
- **18 个控制器、95 个端点全部挂上 `@AuditOp`**：
  Auth / WebAuthn / User / Rbac / Video / VideoList / Document / Preview / Playback /
  Search / Tags / Distribution / Webhooks / Notifications / Watermark / Admin /
  Dashboard / Audit
- **删掉 16 处手写的 `auditService.log(...)`**（含 `AuthController` 里的登录/登出）：
  切面记的信息严格更多（多了 IP、UA、状态码、耗时、失败原因）
- `UserRoleService.assignRoles` 里的手写审计：**死代码**（RbacController 自己内联实现了同一件事），
  一并清掉

### 3.3 播放日志（play_log）落地

`operation_log` 记"谁点了播放"，`play_log` 记"谁把哪个视频看了多久、看到哪儿、有没有水印" ——
后者是**溯源链的最后一环**（方案书 §14.1 把审计日志列为受控资产）。

- 新增 `model/PlayLog` / `repository/PlayLogRepository` / `service/PlayLogService`
- `PlaybackController.reportLog` 真正写库了（此前只有一行 `log.info`）
- 前端 `VideoDetail.vue` 上报 `start` / `pause` / `ended` 事件 + 当前进度秒数 + 水印标记
- `api/client.ts` 新增 `silent` 选项：后台旁路上报失败**不弹错误提示**（用户对埋点失败无能为力）

### 3.4 前端

- 新增 `web/src/constants/audit.ts`：操作类型清单 + 中文标签 + 历史别名 + 翻译函数
- `Audit.vue` 大改：
  - 操作类型下拉改用**后端返回的清单**（后端加了新类型自动跟上），中文标签
  - 新增列：HTTP 方法、命中的端点、状态码、耗时
  - 新增筛选：资源类型、时间范围（后端一直支持 `start_time`/`end_time`，**前端从来没传过**
    ⇒ "按时间查"这个功能事实上不存在）
  - 结果、资源类型、操作全部中文显示
- `AuditOpTypeParity` 单测：**直接读后端 `AuditOpType.java` 源码**，
  把规范名抠出来跟前端常量逐个比对 —— 以后后端加了新操作类型而前端忘了同步，立刻红

---

## 四、验证

- 后端：`mvn test` → **151 个用例，0 失败**
  （`AuditAspectTest` 11、`AuditDetailTest` 6、`AuditOpTypeTest` 8 为新增；
  `ApplicationStartupTest` 6 个 error 是**环境问题** —— 它要求真 MySQL
  （`application-test.yml` 明确写"用真 MySQL 而不是 H2"），本机 3306/3307 都没起）
- 前端：`vitest run` → **53 个用例全过**（新增 8 个）
- 前端：`vue-tsc --noEmit` → 无类型错误

新增测试锁住的三条不变量：

1. 成功 / 失败 / 拒绝 **三种结果都必须留痕**（`AuditAspectTest`）
2. `AccessDeniedException` ⇒ `denied`；返回 403 但不抛异常 ⇒ 也是 `denied`
3. 凭据（password / token / secret / mfa_code…）**一律不出现在审计里**

---

## 五、运维须知

1. **表结构变了**：上线时 Flyway 会跑 `V20261104_1000`。
   新增列全部可空、`user_id` 是放宽约束 ⇒ 存量行不受影响，不需要回填。
2. **审计量会显著上升**：以前一天可能几十条，现在是**每个受控请求一条**。
   `operation_log` 按方案书 §7.3 保留 180 天；量大时要关注
   `idx_resource_time` / `idx_endpoint` 有没有走得上。
3. **别删审计**：`AuditController` 自己的查询与导出也记了审计 ——
   "管理员查了日志然后删了痕迹"这种场景，没有这条记录就查不出来。
4. **敏感字段**：审计 detail 里的凭据由 `AuditDetail` 按**键名**自动打码。
   但如果有人在注解里手写了明文字符串，绕过的是代码而不是机制 ——
   评审时要盯一下 `@AuditOp(detail = "…")` 里有没有塞不该塞的东西。

## 六、没做什么（以及为什么）

- **没有改 `operation_log` 的分表策略**：方案书 §7.3 说按月分表（`operation_log_YYYYMM`），
  但当前是单表 + 索引。分表是 DBA 层面的活儿，且要配套归档/冷存流程，
  不是这次"把操作记全"的目标范围内。
- **没有给审计日志加数字签名 / 区块链存证**：方案书 §14.3 写的是"（可选）"，
  且需要引入外部可信锚点，超出本次范围。
- **没有把 `play_log` 的分片拉取也记成 operation_log**：HLS 分片是**高频**的
  （一个 10 分钟视频几十个分片），全记下来会把审计表淹没。
  分片端点用 `auditSuccess = false`：**只记失败**，成功是噪声、失败才是信号。
- **没有记录请求体（JSON body）的内容**：读 body 要消费输入流，
  在 `OncePerRequestFilter` / 切面里做容易与 Spring 的参数解析打架。
  需要的字段通过 `@AuditOp(detail = "…")` 的 SpEL 显式取，更可控也更安全。
