# 修复"登录提示工号或密码错误，但数据库里一个用户都没有"

- 日期：2026-10-24
- 现象：用 `E000001` / `admin123` 登录 → 提示「工号或密码错误」；
  查库 `sys_user` **一条记录都没有**
- 日志：`G:\data\project\edamlog\`

---

## 根因

初始管理员**不是由迁移建的**，而是由应用启动时的 `AdminBootstrap`
（`CommandLineRunner`）建的。它插入失败时，上一轮我加的 try/catch
把它降级成了「只记 error 日志、不阻断启动」——

于是症状变成：**服务正常起来，但管理员账号没建出来**。
前端只显示「工号或密码错误」，数据库里 sys_user 是空的，
两边对不上，完全没有线索。

插入失败的具体原因：**`sys_user.dept_id` 是 `NOT NULL` 且无默认值**，
而初始管理员天然没有「部门归属」：

```
Field 'dept_id' doesn't have a default value
```

`V20260830_5000__dept_id_nullable.sql`（把该列改为可空）**是上一轮
之后才加的**，服务器上的库还没跑这条 → dept_id 仍 NOT NULL → 插入必然失败。

我用静态脚本复现验证了这个链条（模拟"5000 未应用"时的 schema）：

```
sys_user 的必填列（NOT NULL 且无默认值）:
  dept_id           ✗ 未设置（AdminBootstrap 设的是 null）
  employee_no       ✓
  password_hash     ✓
  real_name         ✓
  username          ✓
```

## 修复

### 1. AdminBootstrap 兜底选一个部门（根因修复）

即使 dept_id 列仍是 NOT NULL，插入也能成功：

```java
// 顺序：显式配置 → sys_dept 第一条 → null
private Long resolveDeptId() { ... }
```

新增配置 `edam.bootstrap.admin-dept-id`（`ADMIN_DEPT_ID`）。

这样"迁移没跑全"不再导致登不进去 —— 兜底比"要求运维记得跑迁移"
可靠得多。

### 2. 自举状态可观测（避免下次又查不出）

- `AdminBootstrap.getBootstrapStatus()` / `getBootstrapError()`
- **`/api/v1/health/ready` 现在返回 `admin_bootstrap` 字段**
  （K8s 探针与运维都能直接看到）
- 启动日志给出**可操作的排查指引**（查 flyway history / 确认
  V20260830_5000 / 确认是 dev profile）

### 3. 新增 `dev/db/_check_admin_insert.py` 静态校验

把"AdminBootstrap 设置的列"与"sys_user 的必填列"自动比对 ——
这类"漏设必填列 → 插入静默失败"的问题以后能提前发现。

### 4. 新增 `scripts/dev-check-admin.sh` 诊断脚本

一次输出：flyway 状态 / 关键迁移是否已应用 / dept_id 约束 /
用户与角色数据 / 启动日志里的自举记录 / 健康端点的自举状态，
末尾给出对应的修复命令。

### 5. 补 4 个 AdminBootstrap 单测

锁住关键行为：
- dev profile 建出的 admin **必须带 dept_id**（不许留 null）
- 显式配置的部门 id 优先
- 插入失败时**状态可见**且不抛异常（服务照常起）
- production profile 且未配密码 → 不建任何账号

## 服务器侧操作

```bash
cd /var/data/project/edam
git pull
./scripts/dev-check-admin.sh    # 先诊断（可选）
./scripts/dev-reset.sh          # 重建库 + 跑完整套迁移 + 启动
```

若只想最小改动（不重建库）：

```sql
ALTER TABLE sys_user MODIFY COLUMN dept_id BIGINT UNSIGNED NULL;
```

然后重启后端即可（AdminBootstrap 会自动建出 E000001 / admin123）。

## 验证

```
后端 compile              OK
后端测试 (29)             OK   ← 新增 4 个 AdminBootstrap 用例
worker 测试 (11)          OK
DB 3 校验 + admin 插入校验  OK
OpenAPI 覆盖率            OK (100%)
前端 lint + vue-tsc + 19   OK
e2e (19)                  OK
helm × 3 overlay          OK
```
