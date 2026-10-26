# 健康探针真的会失败（503）+ 未认证返回 401

- 日期：2026-10-26
- 触发：上一轮改 `AdminBootstrap` 时发现 `/health/ready` 的 down 分支会把
  `admin_bootstrap` 整个丢掉；顺着查下去，发现健康端点**从头到尾就没能失败过**。

---

## 一、健康探针：探针形同虚设

### 现象

K8s 的 `readinessProbe` 用的是 `httpGet`，**只看 HTTP 状态码，不看 body**。
而改造前 `/health/ready` 的实现是：

```java
result.put("status", allOk ? "ok" : "degraded");
...
return allOk ? result : Map.of("status", "down", "components", components);
```

无论依赖是否可用，**返回的永远是 HTTP 200**（body 里写 `status: "down"`）。

后果：MySQL 挂了 → 探针照样绿灯 → Pod 继续留在 Service 的 Endpoints 里 →
流量继续打进来 → 每一个请求都 500。**K8s 的自愈机制完全没被触发。**

这也和 OpenAPI 的契约直接矛盾 —— `doc/openapi.yaml` 里 `/health/ready`
声明了 `503 未就绪`，e2e（`e2e/cypress/e2e/01-auth.cy.js`）也断言状态码
∈ {200, 503}，但实现永远给 200。

### 修复

**1）按依赖的"故障域"分级，而不是一律 down**

共享依赖（Redis / RabbitMQ）挂了，把**所有**副本一起摘出 Service 救不回来
—— 坏的是共享组件本身，摘 Pod 只会让故障面从"一个依赖"扩大成"全站"。
所以只有 **MySQL** 判 down：任何一条业务请求都要读它，它不可用 ⇒ 这个 Pod
一个请求都答不出来 ⇒ 必须摘出 Endpoints。其余依赖只降级为 `degraded`（200）。

| 依赖 | 故障影响 | 就绪判定 |
|---|---|---|
| MySQL | 所有业务请求都失败 | `down` → **503** |
| Redis / RabbitMQ | 共享依赖，摘 Pod 救不回来 | `degraded` → 200 |

**2）`admin_bootstrap` 在 503 时也要带上**

上一轮修的正是这个：down 分支用 `Map.of(...)` 重建响应，把
`admin_bootstrap` / `admin_bootstrap_error` 丢了。运维排查"服务起来了
但登不进去"时，恰恰是最需要这个字段的时候 —— 它要是随 503 一起消失，
排查就又变成瞎猜。

**3）探测必须自带超时**

各客户端自己的超时都远大于探针该花的时间：Hikari `connection-timeout` 是
**30s**、RabbitMQ 默认 **60s**，而 K8s `readinessProbe` 默认只等 **1s**。
不设上限的话，Redis 慢一点就能让探针超时失败 ⇒ **健康**的 Pod 被摘出
Endpoints —— 把"某个共享依赖变慢"放大成"全站不可用"。

因此每个探测都跑在独立线程里并带 1.5s 上限，且三个核心依赖**并发**探测
（串行最坏 4.5s，并发后 ≈ 最慢的那个）。

**4）Helm 探针参数显式化**

`timeoutSeconds` / `failureThreshold` 之前没写，用的是 K8s 默认值（1s / 3），
与上面的耗时对不上。现在显式设成 5s / 3，并加了注释说明为什么。

### 契约对齐

| 端点 | 之前 | 现在 |
|---|---|---|
| `/health/live` | 200，永远成功 | 不变（**只**看进程，绝不碰依赖） |
| `/health/ready` | 永远 200 | MySQL 挂 → **503**；其余降级 → 200 |
| `/health` | 永远 200 | 同 `/health/ready` |
| `/health/components` | 200，扁平结构 | `{overall, components:{...}}`，**需鉴权** |

`/health/components` 之前返回的是扁平 map，而 OpenAPI 的 `ComponentsHealth`
schema 和 mock server 都是 `{overall, components:{...}}` —— 三方不一致，
按 OpenAPI 统一。它现在**需要鉴权**（OpenAPI 里该端点声明了 401，
mock server 之前却是放行的），因为它带 DB 连接池、Redis 内存、磁盘用量
等内部信息。

---

## 二、未认证请求返回 401，而不是 403

### 现象

Spring Security 6 在没有显式配置 `authenticationEntryPoint` 时，默认用的是
`Http403ForbiddenEntryPoint` —— **未认证也返回 403**，而且 body 是空的。

（已从 `spring-security-config-6.3.0.jar` 的字节码确认：
`ExceptionHandlingConfigurer.createDefaultEntryPoint` 里就是
`new Http403ForbiddenEntryPoint()`。）

三个后果：

1. **与 OpenAPI 契约不符**：所有受保护端点声明的是 `401 Unauthorized`，
   实现却给 403 空 body。
2. **前端的 refresh_token 静默续期流程永远不触发**：
   `web/src/api/client.ts` 只在 **401** 时才去用 refresh_token 换新 token
   并重放原请求；403 被当成"权限不足"，直接弹"没有权限执行该操作"。
   而 `access_token` 只有 **10 分钟** TTL（`AuthService.expires_in=600`）、
   refresh_token 7 天 —— 等于**每个用户每 10 分钟就被踢回登录页**。
3. **前端拿不到 detail**：空 body ⇒ `data?.detail` 为空，提示语退化成
   一句没信息量的"请求失败"。

### 修复

新增两个 handler，都返回 **Problem Details**（与 `GlobalExceptionHandler`
同构，字段满足 OpenAPI 的 `Error` schema）：

| 情况 | 状态码 | code | 前端行为 |
|---|---|---|---|
| 未认证（无 token / token 失效） | **401** | `AUTH_401` | 触发 refresh 续期 |
| 已认证但权限不足 | **403** | `AUTH_403` | 弹"没有权限"，不登出 |

同时把 `GlobalExceptionHandler` 里手搓的 Problem Details 构造抽成
`ProblemDetails.of(...)`，三处（全局异常 / 401 / 403）共用同一份形状。

### 顺带修掉的一个竞态

`main.ts` 挂着全局 fetch / XHR 包装器，只要响应是 401 就直接
`handleSessionInvalid`（清 token + 跳登录页）。而它挂在 `readystatechange`
上，**先于** axios 自己的处理被调用 —— 包装器一旦清了 token，axios 随后想用
refresh_token 续期时已经拿不到了。

后端改成返回 401 之后，这个竞态会从"不影响"变成"每次续期都失败"。
所以给 `utils/session.ts` 加了会话恢复计数：api 客户端决定续期时登记，
全局包装器看到有人在处理这个 401 就不再重复登出。

---

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `controller/HealthController.java` | 重写：分级判定、503、探测超时、并发探测、`admin_bootstrap` 全程可见、形状对齐 OpenAPI |
| `security/RestAuthenticationEntryPoint.java` | 新增：401 + Problem Details |
| `security/RestAccessDeniedHandler.java` | 新增：403 + Problem Details |
| `security/SecurityConfig.java` | 接上两个 handler；`/health/components` 移出匿名放行 |
| `exception/ProblemDetails.java` | 新增：三处共用的 Problem Details 构造 |
| `web/src/utils/session.ts` | 新增会话恢复计数（`begin/end/isSessionRecovering`） |
| `web/src/api/client.ts` | 续期期间登记/注销会话恢复 |
| `web/src/main.ts` | 全局 fetch / XHR / unhandledrejection 包装器：续期中不登出 |
| `dev/mock/server.py` | mock 与新契约对齐（`/health/components` 需鉴权；`/health/ready` 同构） |
| `doc/openapi.yaml` | HealthCheck / ComponentsHealth schema 与实现对齐；`/health/ready` 补充 503 语义说明 |
| `helm/edam/values.yaml` + `templates/deployment.yaml` | 探针 `timeoutSeconds` / `failureThreshold` 显式化 |
| `scripts/dev-reset.sh` | 就绪等待改用 `curl -f`（要 200，不要"有响应"）；失败时打印各组件状态 |
| `scripts/dev-check-admin.sh` | 打印各依赖组件状态（哪个挂了直接看出来） |

## 四、验证

```
后端单测（不含需 MySQL 的 ApplicationStartupTest）  42 通过
  HealthControllerTest                    8（503 判定 / 分级 / 降级 / admin_bootstrap 可见 / 探测不挂起 / 形状）
  RestAuthenticationEntryPointTest        2（401 + 403 的 Problem Details 形状）
  SecurityConfigAuthStatusTest            2（真实 SecurityConfig 链路：未认证 → 401）
前端 vitest                               20 通过
  client.spec.ts                          5（新增：续期期间登记会话恢复）
前端 vue-tsc --noEmit                     通过
OpenAPI YAML 解析                          通过
Controller ↔ OpenAPI 覆盖率                100%（82/82）
```

> `ApplicationStartupTest` 需要真 MySQL，本地无实例跑不了（基线同样失败，
> 与本次改动无关）；CI 的 `backend:test` job 带 MySQL service，会跑。

## 五、遗留

- `/health/components` 现在需要鉴权。若有外部监控在匿名拉这个端点，
  需要改成带 token —— 当前仓库内无此调用方。
- `Vault` 探测走 `/v1/sys/health`，未配置 Vault 时报 down 属预期
  （`/health/ready` 不看它，只影响 `/health/components` 的 `overall`）。
