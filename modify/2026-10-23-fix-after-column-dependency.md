# 修复 V20260820 迁移的 `AFTER created_at` 列依赖错误

- 日期：2026-10-23
- 现象：
  ```
  Migration of schema `edam` to version "20260820.1000 - add soft del and session" failed!
  Error Code : 1054
  Message    : Unknown column 'created_at' in 'sys_role_permission'
  ```
- 日志：`G:\data\project\edamlog\backend.log`

---

## 好消息：上一轮的清理生效了

这次的日志与之前**完全不同**：

| 之前（nohup.out） | 现在（backend.log） |
|---|---|
| `Successfully validated 11 migrations`（含陈旧的 V1/V2/V3） | 只有本套 8 个迁移 |
| 卡在 `V20260812_1000` 第 511 行（外键 Error 3780） | **V20260812_1000 已成功**，卡在下一条 V20260820 |

说明 `dev-reset.sh` 的三件事都生效了：
`target/` 的陈旧迁移清了、MySQL 重建了、Flyway 独占 schema。

**这次是迁移脚本自身的真 bug。**

---

## 根因：`ALTER ... AFTER <列>` 依赖了一个不存在的列

`V20260820_1000__add_soft_del_and_session.sql`：

```sql
ALTER TABLE sys_role_permission
    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 AFTER created_at;
```

而 `V20260812_1000`（init）建的 `sys_role_permission` 只有：

```sql
id, permission_id, role_id, PRIMARY KEY (id), UNIQUE KEY uk_role_permission, KEY idx_permission_id
```

**没有 `created_at` 列** → MySQL 直接报
`Error 1054: Unknown column 'created_at' in 'sys_role_permission'`。

同类问题还有一处（`sys_role` / `sys_user_role` 的 AFTER 依赖的列都存在，
所以只有这一条炸了）。

### 为什么之前没查出来

我上一轮加的三个静态校验脚本查的是：
- model 字段 ↔ schema 列
- 悬空 ALTER / 悬空 INSERT / 重复建表
- 外键两端类型

**没有查 `AFTER <列>` 的依赖**。这类错误语法合法、静态看不出来，
只有真跑 MySQL 才炸 —— 而 CI 的 `db:migrate` job 至今没跑到这一步。

---

## 修复

### 1. V20260820 去掉 `AFTER created_at`

```diff
 ALTER TABLE sys_role_permission
-    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 AFTER created_at;
+    ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0;
```

`created_at` / `constraint_def` 本来就由
`V20260830_2000__align_model_columns.sql` 补齐（那才是与 Java model 对齐的地方），
所以这里不需要指定列位置。

> 可改的依据：`V20260820` **从未在任何环境成功应用过**（每次都在这一行炸），
> 改它不影响任何已记录的 checksum。而 `V20260812_1000` 已在服务器上成功应用，
> **不能改**。

### 2. 校验脚本补上这个维度

`dev/db/_validate_migrations.py` 新增检查：
每条 `ALTER TABLE t ADD COLUMN c ... AFTER x` 都校验 `t.x` 在那个时点存在，
不存在则报：

```
[20260820.1000] ...: ALTER sys_role_permission ADD COLUMN version AFTER created_at
—— 但 sys_role_permission.created_at 在该时点不存在
（运行时报 Error 1054: Unknown column 'created_at'）
```

**已验证检查器确实能抓到**（临时把 bug 改回去 → 校验器报错 → 还原 → 通过），
不是假阳性。

### 3. 校验器按"真实运行时语义"建模

同一张表被建过两次时（`CREATE TABLE IF NOT EXISTS` 是空操作），
最终结构 = 第一次 CREATE + 后续 ALTER 增量 —— 与 MySQL 行为一致。

---

## 验证

```
dev/db/_validate_migrations.py   → 迁移链自洽，exit 0
dev/db/_validate_model_schema.py → 182/182 字段匹配，exit 0
dev/db/_validate_fk_types.py     → 25 条外键类型兼容，exit 0
```

`SysRolePermission` 的 model 字段（id / roleId / permissionId /
constraintDef / createdAt）最终全部落到 schema 里。

---

## 服务器侧

```bash
cd /var/data/project/edam
git pull
./scripts/dev-reset.sh
```

> 注：这次 `V20260812_1000` 已经在库里成功应用了，改动不涉及它，
> 但 `dev-reset.sh` 仍然建议跑一遍 —— 上一条 V20260820 的失败记录
> 还在 `flyway_schema_history` 里（`success=0`），不清掉 Flyway 会拒绝启动。
