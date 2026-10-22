# 修复「陈旧编译产物里的废弃迁移」导致 Flyway 加载两套 schema

- 日期：2026-10-22
- 现象：
  ```
  Successfully validated 11 migrations
  Migrating schema `edam` to version "1 - init schema"          ← V1 又回来了
  Migrating schema `edam` to version "2 - widen mime type"       ← V2
  Migrating schema `edam` to version "3 - rbac complete"         ← V3
  Migrating schema `edam` to version "20260812.1000 - init schema"
  ...
  Error Code : 3780
  Referencing column 'dept_id' and referenced column 'id' in
  foreign key constraint 'fk_user_dept' are incompatible.
  ```
- 日志：`G:\data\project\edamlog\nohup.out`

---

## 元凶：`backend/target/classes/db/migration/` 里的陈旧产物

**数据库是全新的**（日志：`Current version of schema 'edam': << Empty Schema >>`），
所以上一轮怀疑的「库被旧 schema 污染」**不是**这次的原因。

真正原因是 **Flyway 加载了 11 个迁移** —— 比源码里的 8 个多 3 个：

```
源码 backend/src/main/resources/db/migration/   →  8 个 ✅
target/classes/db/migration/                    → 11 个 ❌ 多出 V1 / V2 / V3
```

| 文件 | 编译产物时间 | 源码 |
|---|---|---|
| `V1__init_schema.sql` | 8-24 | 已删（上轮统一到 V2026* 系列） |
| `V2__widen_mime_type.sql` | 8-25 | 已删 |
| `V3__rbac_complete.sql` | 8-27 | 已删 |

**Maven 的 resources 插件只复制/覆盖，不删除**源里已移除的资源。
所以 `git rm` 删掉源文件后，`target/classes/` 里的旧脚本会永远留着。

### 完整因果链

1. 上轮从源码删掉 V1/V2/V3（统一到 V2026* 系列）→ `target/` 里的旧副本还在
2. 启动时 Flyway 扫描 classpath → 看到 **11 个**迁移
3. 先跑 V1（建旧结构 `sys_role_permission`：permission/action/role_code）
4. 再跑 V3（重建 RBAC 表 + 灌种子）
5. 跑 V20260812_1000 → `CREATE TABLE IF NOT EXISTS` **静默跳过**上面那些表
6. 到文件末尾加外键约束时，`sys_role_permission.role_id`（V1/V3 建的
   **signed BIGINT**）引用 `sys_role.id`（本套 **UNSIGNED**）
   → **Error 3780 are incompatible**

而这 25 条外键里，任何一条跨"新旧两套"表的都会炸 —— 报错位置在
init 脚本第 500+ 行，离病因（target 目录有陈旧产物）极远。

---

## 修复

### 1. 启动期守卫（把神秘 FK 错误变成明确提示）

新增 `backend/src/main/java/com/example/edam/config/FlywayMigrationGuard.java`：
用 `FlywayMigrationStrategy` 替代默认的 migrate 流程，
**迁移开始前**扫一遍 classpath 上的 `db/migration/*.sql`，
发现已知废弃的 V1/V2/V3 就直接抛异常，并明确告诉运维：

```
检测到 classpath 上存在**已废弃的迁移脚本**：V1__init_schema.sql, V3__rbac_complete.sql。
这些是历史 schema 的残留（backend/target/classes/db/migration/ 下 Maven 编译产物没被清理）。
它们会让 Flyway 同时加载两套 schema，导致外键约束报 'are incompatible'（Error 3780）。
请先执行 `mvn clean`（或删除 backend/target 目录）再重新启动。
```

> 只列**确定废弃**的三个文件 —— 误报会让正常环境起不来。

### 2. CI 加 `mvn clean`

```diff
- - mvn -B test -DfailIfNoTests=false
+ # 必须 clean：Maven 的 resources 插件只复制/覆盖，不删除源里已移除的资源
+ - mvn -B clean test -DfailIfNoTests=false
```

CI 每次是全新 checkout，`target/` 本来是空的，但加上 clean 可以
**杜绝**将来再删迁移文件时踩同一个坑。

### 3. 本地一键清理脚本

`scripts/dev-clean-backend.sh`：
```bash
./scripts/dev-clean-backend.sh          # 只删 target
./scripts/dev-clean-backend.sh --run    # 清理后直接启动后端（开发端口 8092）
```

### 4. 上一轮加的 init 预检（本轮日志里已验证生效）

```
| edam_schema_precheck |
| ⚠️ EDAM 迁移预检：检测到本库存在旧版 schema 的表 ... |
```

预检能识别"库里有 signed BIGINT 的 FK 列"这一类污染，
但**识别不了"classpath 上有废弃脚本"**（那是另一个维度）。
本轮的守卫补上了这个维度。

---

## 用户侧操作

```bash
cd /var/data/project/edam/backend
mvn clean compile          # 或 rm -rf target
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092
```

或者直接用仓库里的脚本：
```bash
./scripts/dev-clean-backend.sh --run
```

**数据库不需要重建** —— 这次的库本来就是全新的。

---

## 验证

```
mvn clean compile 后 target/classes/db/migration/  →  8 个（陈旧产物已清）
守卫扫描逻辑（把 V1/V3 塞回 target 复现）      →  ✅ 正确检出
后端 mvn -o compile                            →  通过
```

## 教训

**「删掉源文件」不等于「产物里没了」。** Maven / Gradle 的增量构建
对**资源文件**是"只增不减"的 —— 这条规则适用于任何
`src/main/resources` 下的文件（不只是迁移脚本：
配置模板、静态资源、mapper XML 都一样）。
凡是要删 resources 里的文件，必须配套 `mvn clean`。
