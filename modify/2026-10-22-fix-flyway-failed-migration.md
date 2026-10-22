# 修复 Flyway "Detected failed migration to version 20260812.1000"

- 日期：2026-10-22
- 现象：后端启动失败
  ```
  Validate failed: Migrations have failed validation
  Detected failed migration to version 20260812.1000 (init schema).
  Please remove any half-completed changes then run repair to fix the schema history.
  ```
- 日志：`G:\data\project\edamlog\error_202610221138.txt`

---

## 根因

上一轮（`2026-10-21-p1-hardening-and-convergence.md`）把两套迁移合并时，
**直接修改了 `V20260812_1000__init_schema.sql`** —— 把
`sys_user_role.expire_at` 改成 `expires_at`、`mime_type` 拓宽到 255、
给 `sys_permission` / `sys_role_permission` 补列。

这违反了 Flyway 的铁律：**已应用过的迁移文件永不修改**（改了 checksum 对不上）。
更糟的是：那个环境里这条迁移本来就**执行到一半失败过**
（`flyway_schema_history` 里有 `success = 0` 的行），Flyway 看到失败记录
就拒绝启动 —— 于是启动时先报 failed migration，而不是 checksum mismatch。

---

## 修复

### 1. 还原已应用的迁移文件

`V20260812_1000__init_schema.sql` 恢复成原始内容（checksum 与库里一致）：

```bash
git show 5308c34:dev/db/migration/V20260812_1000__init_schema.sql \
  > backend/src/main/resources/db/migration/V20260812_1000__init_schema.sql
```

### 2. 修正全部挪进**新迁移**

`V20260830_2000__align_model_columns.sql`（本轮新建）：

| 修正 | 内容 |
|---|---|
| `sys_user_role` | `expire_at` → `expires_at`（+ 索引改名） |
| `sys_permission` | 补 `action` / `description` / `is_system` / `created_at` |
| `sys_role_permission` | 补 `constraint_def` / `created_at` |
| `mime_type` | `video_resource` / `doc_resource` / `file_metadata` 拓宽到 255 |
| `video_resource` | 补 `updated_at` |
| `webhook_delivery` | 补 `created_at` |
| `sys_session` | **补 7 列**（见下） |

### 3. 顺带发现并修掉一个潜伏 bug：`sys_session` 少 7 列

`sys_session` 被**两个**迁移建过：
- `V20260812_1000`（init）里 `CREATE TABLE IF NOT EXISTS sys_session`（缺 7 列）
- `V20260820_1000` 里又建一次（列更全）

MySQL 的 `CREATE TABLE IF NOT EXISTS` 在表已存在时是**空操作**，
而 init 版本号更小、先跑 —— 所以最终落地的是 init 那份缺 7 列的结构，
V20260820 那 7 列**永远不会出现**：

`access_token_jti` / `device_fingerprint` / `login_method` /
`access_token_expire_at` / `revoked_at` / `revoked_reason` / `version`

任何读/写 `SysSession` 的地方都会报 `Unknown column`。

**上一轮我用的校验脚本有缺陷**：它把多次 CREATE 的列取了**并集**，
掩盖了"先到先得"的真实语义，所以没查出来。本轮重写为按
"第一次 CREATE 生效 + 后续 ALTER 增量"建模，172 个字段里立刻查出这 7 处缺失。

---

## 新增校验工具

| 文件 | 作用 |
|---|---|
| `dev/db/_validate_model_schema.py` | 按**真实运行时语义**（先建先生效 + ALTER 增量）核对 20 个 model 的 172 个字段 ↔ schema 列 |
| `dev/db/_validate_migrations.py` | 按版本顺序校验迁移链自洽（无悬空 ALTER / 重复版本 / 重复建表） |
| `dev/db/repair-flyway.sql` | Flyway schema history 修复指引（方案 A 重建 / 方案 B 原地 repair） |

**建议把 `_validate_model_schema.py` 接进 CI 的 `db:migrate` job** ——
它能在没有真库的环境里抓出"model 与 schema 不匹配"这类问题。

---

## 用户侧操作（必须做一次）

Flyway 的失败记录不会自己消失，需要人工清理。

### 方案 A —— 开发/测试环境（推荐，最干净）

```sql
DROP DATABASE edam;
CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后重启后端，Flyway 会从零跑完整套：
`V20260812_1000 → … → V20260830_1000 → V20260830_2000`。

### 方案 B —— 生产环境（不重建）

1. 备份数据库
2. 查现状：`SELECT * FROM flyway_schema_history WHERE success = 0;`
3. 删失败记录：`DELETE FROM flyway_schema_history WHERE success = 0;`
4. 清理半成品表（先跑 `dev/db/repair-flyway.sql` 第 2 节的查询看有哪些表，
   再逐个 DROP）
5. `flyway ... -locations=filesystem:backend/src/main/resources/db/migration repair`
6. 重启后端

> 注意：如果该环境当初是用**旧的 backend V1/V2/V3** 那套迁移建的库，
> `flyway_schema_history` 里会有版本 1/2/3 的记录，与现在的版本号体系
> 对不上 → Flyway 会报
> "Detected applied migration not resolved locally: 1"。
> 这种情况下**只能走方案 A 重建**（或手工删掉历史表里的旧版本行，
> 但那样 schema 状态不可信，不建议）。

---

## 验证

```
dev/db/_validate_model_schema.py
  → 运行时表数 35 / 核对字段 172 / ✅ 全部匹配 / 迁移链仅剩 1 条已说明的冗余建表
dev/db/_validate_migrations.py
  → 迁移链自洽性检查通过
```
