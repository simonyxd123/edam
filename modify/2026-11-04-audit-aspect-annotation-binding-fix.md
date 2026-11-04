# 审计切面注解绑定修复 —— 上线后登录 500 的根因

- 日期：2026-11-04
- 触发：审计日志全量覆盖上线后，**所有被审计的接口一律 500**（登录首当其冲）
- 涉及方案书章节：**§7.1 核心数据表**（operation_log 全审计）、
  **§14.3 STRIDE**（Repudiation 否认 → 操作日志全审计）

---

## 一、症状

线上日志（`nohup.out`）里每一条被审计的请求都是同一个错：

```
ERROR c.e.e.e.GlobalExceptionHandler - Unhandled exception
java.lang.IllegalStateException: Required to bind 2 arguments, but only bound 1
    (JoinPointMatch was NOT bound in invocation)
    at org.springframework.aop.aspectj.AbstractAspectJAdvice.argBinding(AbstractAspectJAdvice.java:601)
    at org.springframework.aop.aspectj.AbstractAspectJAdvice.invokeAdviceMethod(AbstractAspectJAdvice.java:627)
    at org.springframework.aop.aspectj.AspectJAroundAdvice.invoke(AspectJAroundAdvice.java:71)
    at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:173)
    at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:720)
    at com.example.edam.controller.AuthController$$SpringCGLIB$$0.login(<generated>)
```

因为切面挂在**所有**控制器方法上，所以这不是"登录接口坏了"，是**整个系统全挂**。

---

## 二、根因：切面的 order 抢在了 Spring 的 `ExposeInvocationInterceptor` 前面

原来的 advice 写法是"让 Spring 把注解绑成形参"：

```java
@Around("@annotation(auditOp)")
public Object around(ProceedingJoinPoint pjp, AuditOp auditOp) { … }
```

这种写法要成立，Spring 必须能把 `@AuditOp` 的实例从连接点上取出来交给 `auditOp` 形参。
取它的机制是 `JoinPointMatch`，而 `JoinPointMatch` 来自
`ExposeInvocationInterceptor` 暴露的当前 `MethodInvocation` ——
**只有它跑过之后才拿得到**。

关键在于它的 order：

| 组件 | order | 位置 |
|---|---|---|
| `ExposeInvocationInterceptor` | `HIGHEST_PRECEDENCE + 1` = -2147483647 | 拦截器链靠前 |
| **本审计切面** | `HIGHEST_PRECEDENCE` = -2147483648 | **比它还靠前** |
| Spring Security 方法级授权（`@PreAuthorize`） | 约 100 | 靠后 |

本切面用 `HIGHEST_PRECEDENCE` 是有意的 —— 只有排在最外层，
才能把 `@PreAuthorize` 抛出的 `AccessDeniedException` 也包进来
（否则"越权尝试"这类最该记的审计一条不落）。

**两个需求撞在了同一个 order 上**：
要包住授权拦截器 ⇒ 必须排最前 ⇒ 抢在 `ExposeInvocationInterceptor` 前面
⇒ 注解绑不上 ⇒ 每个接口 500。

Spring 自己在 `ExposeInvocationInterceptor.currentInvocation()` 的异常信息里
把这条坑写得一清二楚：

> *"Check that an AOP invocation is in progress and that the ExposeInvocationInterceptor
> is upfront in the interceptor chain. **Specifically, note that advices with order
> HIGHEST_PRECEDENCE will execute before ExposeInvocationInterceptor!**"*

---

## 三、为什么单测没发现

`AuditAspectTest` 是**直接调 advice 方法**的：

```java
aspect.around(pjp, opOf("success"));
```

根本没经过 Spring 的 AOP 代理，也就没经过参数绑定 ——
**这条链路上出问题的地方，一行都没跑到**。

而且坑的复现条件很挑：必须在**有 Spring Security 过滤器链的 Web 上下文**里才复现。
纯 POJO 的 `@SpringJUnitConfig` 上下文里，拦截器链的组成不同，
`ExposeInvocationInterceptor` 恰好排在了切面**前面**，于是老写法照样跑得通。

> 这两点合起来解释了为什么它在本地全绿、到了线上全挂。

---

## 四、修法：注解自己取，不靠 Spring 绑

### 4.1 代码

```java
@Around("@annotation(com.example.edam.audit.AuditOp)")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    AuditOp auditOp = resolveAuditOp(pjp);   // ← 自己从方法签名上取
    …
}

/** 从连接点里取出 @AuditOp 注解实例 */
private AuditOp resolveAuditOp(ProceedingJoinPoint pjp) {
    Method method = ((MethodSignature) pjp.getSignature()).getMethod();
    Object target = pjp.getTarget();
    if (target != null) {
        // CGLIB 代理上拿到的可能是代理类的方法，注解不在那儿 —— 换成目标类的真实方法
        Method specific = AopUtils.getMostSpecificMethod(method, target.getClass());
        if (specific != null) method = specific;
    }
    AuditOp op = AnnotatedElementUtils.findMergedAnnotation(method, AuditOp.class);
    …
}
```

切面从此**不依赖 `JoinPointMatch`**，也就和拦截器链的顺序彻底解耦 ——
`@Order(HIGHEST_PRECEDENCE)` 想放多靠外就放多靠外，
"包住 `@PreAuthorize`" 这个设计意图不受任何影响。

### 4.2 切点必须写成类型形式

```java
@Around("@annotation(auditOp)")          // ❌ 变量形式
@Around("@annotation(com.example.edam.audit.AuditOp)")   // ✅ 类型形式
```

`@annotation(auditOp)` 里的 `auditOp` 是**切点参数**，由 advice 方法的形参提供。
advice 不再有这个形参 ⇒ 切点解不出这个变量 ⇒ AspectJ 解析失败 ⇒
**切面静默不生效**（不报错、不记录，比抛异常更难发现）。所以必须用全限定类名。

### 4.3 为什么不是"把 order 挪一下就完事"

把切面改成 `HIGHEST_PRECEDENCE + 2` 也能绕过（让它排在
`ExposeInvocationInterceptor` 后面、授权拦截器前面）。
但那是**把正确性挂在 Spring 内部实现的 order 数值上** ——
Spring 改一次版本就可能又炸，而且炸的时候是"接口全 500"，代价极高。
自己取注解没有这个耦合。

---

## 五、验证

### 5.1 复现（证明根因判断没错）

用**和线上同构**的上下文（MockMvc + Spring Security 过滤器链 + `@RestController`）
跑老写法，得到**一模一样**的报错：

```
jakarta.servlet.ServletException: Request processing failed:
  java.lang.IllegalStateException: Required to bind 2 arguments, but only bound 1
  (JoinPointMatch was NOT bound in invocation)
```

### 5.2 新增集成测试（`AuditAspectIntegrationTest`，3 条）

真实发 HTTP 请求，走完整链路，锁住三条：

1. 带 `@AuditOp` 的接口**确实**被切到，并按注解里的声明落库（成功）
2. 接口抛异常 ⇒ 记 `failure`
3. `@PreAuthorize` 被拒 ⇒ 记 `denied`（同时证明切面仍排在授权拦截器**外面**）

### 5.3 全量

- 后端：`mvn test` → **154 个用例，0 失败**
  （`ApplicationStartupTest` 的 6 个 error 仍是**环境问题** ——
  `application-test.yml` 要求连真 MySQL，本机 3306/3307 没起，与本次改动无关）
- 前端：本次未改动前端

---

## 六、教训（写给下一个人）

1. **切面必须过真实代理测**。直接调 advice 方法的单测，
   只能验业务逻辑，验不了 Spring 的切点匹配与参数绑定。
2. **"本机跑得好好的"不等于线上没事**。这个坑在纯 POJO 上下文里不复现，
   复现它需要 Web + Security 过滤器链 —— 也就是生产的形状。
3. **审计切面的 order 不是随便定的**。它同时要满足
   "排在授权拦截器外面"和"别抢 Spring 内部拦截器的位置"，
   后者靠"不依赖框架内部机制"来保证，而不是靠调数值。
4. 审计本身**必须**做到"永不拖垮业务"：这次是 Spring 在**进入 advice 方法之前**
   就抛了异常，切面连"记一条失败"的机会都没有 —— 这类失败只能靠框架层修。

---

## 七、上线须知

- **只改后端**，前端无需重新构建。
- 不涉及数据库变更（上一版审计改造的 `V20261104_1000` 迁移照旧）。
- 部署后建议抽查一条：`SELECT operation_type, result, endpoint FROM operation_log
  ORDER BY id DESC LIMIT 10;` —— 应当能看到 `login` / `list` / `detail` 等记录，
  且 `result` 有 `success` / `failure` / `denied` 三种取值。
