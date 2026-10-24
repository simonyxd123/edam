# 初始管理员：dev fail fast + 一键脚本主动验证

- 日期：2026-10-24
- 现象：`git pull` + `./scripts/dev-reset.sh` 跑完，**E000001/admin123 仍没建出来**

---

## 问题分析

上一轮修了"dept_id NOT NULL 导致插入失败"，但只解决了**根因的一半**：

| 层面 | 上一轮 | 本轮 |
|---|---|---|
| 根因 | dept_id 兜底选部门 | 保留 |
| **失败可见性** | dev 也只是记 error 日志 → 服务正常起来但登不进去 | **dev fail fast**：直接抛异常，进程退出 1 |
| **一键脚本** | 启动完就退出，让你自己去查 | **主动等就绪 + 主动查库**，查不到就把原因摆出来并 exit 1 |
| **真实验证** | 只有 mock 单测 | CI 的**真 MySQL** 上验证 admin 真的被建出来 |

"启动成功但登不进去"是最难查的一类症状 —— 服务健康检查全绿、日志里
只有一行 error、前端只显示"工号或密码错误"。

## 修复

### 1. dev profile 下自举失败 → fail fast

```java
if (isDevProfile()) {
    throw new IllegalStateException(
        "dev profile 下初始管理员创建失败，服务无法登录。", e);
}
log.warn("admin_bootstrap_failed —— production profile 不阻断启动，"
    + "请通过运维通道手工创建管理员账号。");
```

语义分清：
- **dev**：没有初始管理员 = 环境不可用（登录页都进不去）→ 必须明确失败
- **production**：管理员账号要走运维通道建，自举失败不该让后端起不来
  （连监控都看不到）→ 降级 + 状态暴露

### 2. `dev-reset.sh` 主动验证结果

新增第 4 步：

```
==> 等待后端就绪并验证初始管理员…
    轮询 /api/v1/health/ready（最多 120s）
    ├─ 没就绪      → 打印日志尾部 30 行 + exit 1
    ├─ admin_bootstrap=failed
    │                → 打印 /health/ready 里的 admin_bootstrap_error
    │                  + 日志里的 admin_bootstrap_failed 段落 + exit 1
    └─ 直接查库 SELECT COUNT(*) FROM sys_user WHERE employee_no='E000001'
         └─ 0 行 → 提示跑 dev-check-admin.sh + exit 1
```

脚本从此是「要么成功、要么明确失败」，不再是「启动完就退出让你自己看」。

### 3. CI 的真 MySQL 上验证 admin 真的被建出

`ApplicationStartupTest` 新增用例：

```java
@Test
@DisplayName("初始管理员 E000001 已被建出，且密码可用")
void initialAdminAccountIsCreated() {
    SysUser admin = userRepository.findByEmployeeNo("E000001");
    assertNotNull(admin, "初始管理员未创建 —— 查 backend/backend.log 里的 admin_bootstrap_failed");
    assertTrue(passwordEncoder.matches("Test@123456", admin.getPasswordHash()), ...);
}
```

这个用例在 CI 的 `backend:test` job 里跑**真迁移 + 真插入** ——
任何一步断了都会红。之前的 mock 单测验证不了"真能插进去"。

### 4. 测试相应更新

`AdminBootstrapTest` 拆成两个语义：
- dev 失败 → `assertThrows(IllegalStateException.class, ...)`（fail fast）
- production 失败 → `assertDoesNotThrow(...)` + 状态可见

---

## 服务器侧

```bash
cd /var/data/project/edam
git pull
./scripts/dev-reset.sh
```

这次脚本会**自己验证**并明确告诉你结果：
- 成功 → `✓ 初始管理员 E000001 已建出`
- 失败 → 直接打印失败原因（health 端点的 admin_bootstrap_error +
  日志里的 admin_bootstrap_failed 段落），并 exit 1

若只想手工确认：`./scripts/dev-check-admin.sh`

## 验证

```
后端 compile              OK
后端测试 (30)             OK   ← 新增 fail-fast / 降级 两个语义用例
worker 测试 (11)          OK
DB 4 校验                 OK   ← 新增 admin 插入路径校验
OpenAPI 覆盖率            OK (100%)
前端 lint + vue-tsc + 19   OK
e2e (19)                  OK
helm × 3 overlay          OK
4 个脚本语法              OK
```
