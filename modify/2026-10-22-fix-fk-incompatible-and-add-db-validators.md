# 修复 Flyway Error 3780（外键两端类型不兼容）+ 增加 DB 静态校验

- 日期：2026-10-22
- 现象：
  ```
  Migration V20260812_1000__init_schema.sql failed
  Error Code : 3780
  Message    : Referencing column 'dept_id' and referenced column 'id'
               in foreign key constraint 'fk_user_dept' are incompatible.
  Line       : 511
  ```
- 日志：`G:\data\project\edamlog\error_202610221556.txt`

---

## 根因：这个库里混着**两套 schema**

| 表 | 来源 | 整数字类型 |
|---|---|---|
| `sys_user` | 旧 backend `V1__init_schema.sql`（`CREATE IF NOT EXISTS` **静默跳过**） | `BIGINT`（**signed**） |
| `sys_dept` | 新 `V20260812_1000`（新建） | `BIGINT UNSIGNED` |

MySQL 要求外键两端的列**类型与符号性完全一致**，
signed 引用 unsigned → Error 3780。

而且不只是类型问题 —— 旧 schema 与新 schema 的**列集**也差很多：

| 表 | 旧 schema 缺的列 / 结构差异 |
|---|---|
| `sys_user` | 缺 `must_change_password`、`password_changed_at` |
| `sys_role` | 缺 `deleted_at`、`version` |
| `sys_session` | 缺 7 列 |
| `video_resource` | 缺 `updated_at` |
| `webhook_delivery` | 缺 3 列 |
| `sys_role_permission` / `video_permission` / `doc_permission` / `play_log` | **表结构完全不同**（`permission`/`action`/`role_code` vs `permission_id`/`role_id`/`constraint_def`） |

所以**任何 ALTER 修补方案都不现实** —— 只能重建库。

这也解释了整个症状演变：
1. 最初：`Detected failed migration to version 20260812.1000`
   （迁移在第 511 行失败过，history 里有 `success=0` 的行）
2. 还原 init 后：`Migration V20260812_1000 failed` @ Line 511
   （历史被清掉后重跑，这次是真正卡在 FK）

---

## 修复

### 1. 新增重置脚本 `dev/db/reset-dev-db.sql`

```sql
DROP DATABASE IF EXISTS edam;
CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

含「什么时候需要跑」「为什么必须重建而不是 repair」的说明。

### 2. init 迁移顶部加**预检提示**

在 `V20260812_1000__init_schema.sql` 开头加一段检测：查
`information_schema.COLUMNS` 里 FK 相关列是否为 signed BIGINT
（旧 schema 的特征），命中就输出一条明确的指引。

**为什么用恒成功的 `SELECT` 而不是 `SIGNAL` 抛错**：
`SIGNAL` 必须在存储程序里用（`CREATE PROCEDURE`），而
`CREATE ROUTINE` 是**全局权限** —— 生产用最小权限数据库账号时，
为了一个提示反而会把迁移本身搞挂。这条 SELECT 的结果会出现在
Flyway 的 migrate 输出里，迁移若随后失败，日志里紧挨着就有指引。

### 3. 三个静态校验脚本（不需要真库）

| 脚本 | 检查什么 | 能提前抓到 |
|---|---|---|
| `dev/db/_validate_model_schema.py` | 20 个 model 的 172 个字段 ↔ 运行时 schema 列 | `Unknown column 'xxx' in 'field list'` |
| `dev/db/_validate_migrations.py` | 迁移链自洽（版本序、悬空 ALTER/INSERT、重复建表） | 迁移执行到一半才炸 |
| `dev/db/_validate_fk_types.py` | 全新库上每条外键两端类型/符号性是否兼容 | **Error 3780** |

**关键点**：这三个脚本都按 **Flyway 的真实运行时语义**建模 ——
`CREATE TABLE IF NOT EXISTS` 在表已存在时是**空操作**，
所以最终结构 = 第一次 CREATE 的那份 + 后续所有 ALTER 的增量。
（第一版校验脚本把多次 CREATE 的列取了**并集**，正好掩盖了
`sys_session` 少 7 列的问题。）

`_validate_fk_types.py` 本次验证结果：
```
检查外键约束: 25 条
✅ 全部外键两端类型兼容（全新库可正常建约束）
```
→ 证明**全新库上迁移脚本本身是对的**，问题纯粹是环境污染。

### 4. 接进 CI

`db:migrate` job 在真正跑 flyway **之前**先跑这三个脚本 ——
这类问题不必等到部署才发现。

---

## 用户侧操作（必须做一次）

```sql
-- 开发/测试环境
DROP DATABASE edam;
CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

重启后端即可。

> 生产环境若被旧 schema 污染，同样只能重建（结构差异太大，
> 手工对齐的风险远高于重建）；且旧表里的数据本来也与当前
> Java model 不兼容（读出来就是错的）。

---

## 验证

```
dev/db/_validate_model_schema.py  → 172/172 字段匹配，exit 0
dev/db/_validate_migrations.py   → 迁移链自洽，exit 0
dev/db/_validate_fk_types.py     → 25 条外键类型全兼容，exit 0
.gitlab-ci.yml                    → YAML 解析 10 jobs
```
