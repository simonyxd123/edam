# 后端测试从零到有 + CI 启动冒烟

- 日期：2026-10-21
- 范围：backend/src/test/**、.gitlab-ci.yml

---

## 背景：后端**一个测试类都没有**

```
find backend/src/test -name "*.java" | wc -l   →  0
```

而 CI 的 `backend:test` 跑的是 `mvn -B clean test -DfailIfNoTests=false`
—— `-DfailIfNoTests=false` 明确允许**零测试通过**。

结果：所有启动期问题 —— Bean 装配、Flyway 迁移、model↔schema 不匹配、
`AdminBootstrap` 插入失败 —— 全都是在服务器上一轮轮试出来的
（连续 5 轮），而不是被 CI 拦住的。

## 新增测试

| 测试 | 覆盖什么 | 依赖 |
|---|---|---|
| `ApplicationStartupTest` | **全上下文启动** + Flyway 在真 MySQL 上跑全套迁移 + `AdminBootstrap` 插入路径 + MyBatis 部分字段插入 | MySQL + RabbitMQ |
| `TotpUtilTest` | RFC 6238 **官方测试向量**（6 个）+ fail-closed 行为 + base32 往返 | 无 |
| `ClientIpResolverTest` | 受信代理 CIDR 匹配 + 伪造 XFF 被忽略 | 无 |
| `SsoRequestPathsTest` | context-path 剥离 + 前缀不匹配返回 null + providerId 含斜杠拒绝 | 无 |
| `ResourceEnumsTest` | 4 组枚举映射 | 无 |
| `PaginationResponsesTest` | 分页壳字段名与前端 `PageResult` 对齐 | 无 |

**25 个纯逻辑测试本地全过**（本机无 MySQL，冒烟测试留给 CI）。

### TOTP 用 RFC 官方向量而不是自证

`TotpUtilTest` 硬编码 RFC 6238 附录 B 的 6 个测试向量
（secret = ASCII `12345678901234567890`），用自己实现的
`generate()` 算出的值去比对 —— 不用自己的 `verify()` 验证自己的
`generate()`，那样测不出算法错误。

## CI：backend:test 真正跑测试

```diff
 backend:test:
   stage: test
   image: maven:3.9-eclipse-temurin-17
+  services:
+    - name: mysql:8.0
+      alias: mysql
+    - name: rabbitmq:3.13-alpine
+      alias: rabbitmq
+  variables:
+    TEST_MYSQL_HOST: "mysql"
+    ...
   script:
-    - mvn -B clean test -DfailIfNoTests=false
+    - (等 MySQL 就绪的循环)
+    - mvn -B clean test        # 不再给 -DfailIfNoTests=false
```

**MySQL 而不是 H2**：迁移脚本是 MySQL 方言（`DATETIME(3)` /
`ENGINE=InnoDB` / `COMMENT` / `ALTER ... AFTER`），H2 的 MySQL 兼容模式
跑不了 —— 测了等于没测。

**RabbitMQ** 是因为 `RabbitAdmin` 在启动时就要连（声明队列）。

## 新增 `backend/src/test/resources/application-test.yml`

测试 profile 配置：数据源指向 CI 的 service、Flyway 真实执行、
`edam.bootstrap.admin-password` 配好让 `AdminBootstrap` 真的建一次
管理员（**它的插入路径正是这次线上故障的来源**）。

### 踩坑：MySQL 驱动类名

第一版写成 `com.mysql.cj.driver.Driver`（少了 `jdbc`）→ 报
`Cannot load driver class`。正确是 `com.mysql.cj.jdbc.Driver`。
主配置 `application.yml` 没写 `driver-class-name`（靠自动探测），
所以主应用没事，只有测试配置踩到。

## 验证

```
mvn test -Dtest='TotpUtilTest,ClientIpResolverTest,SsoRequestPathsTest,
                   ResourceEnumsTest,PaginationResponsesTest'
  → Tests run: 25, Failures: 0, Errors: 0

ApplicationStartupTest（本机无 MySQL）
  → 失败信息清晰：Communications link failure
    （证明测试确实会执行、不会静默跳过；CI 上有 service 可通过）
```

## 遗留

`ApplicationStartupTest` 需要 CI 首次跑一次才能确认它在
mysql + rabbitmq service 下能过（`@SpringBootTest` 的 MOCK 环境下
STOMP endpoint / Security 装配是否都 OK，本机无法验证）。
第一次 CI 跑完若红，按报错修即可 —— 至少这次是**在 CI 上**发现，
而不是在生产服务器上一轮轮试。
