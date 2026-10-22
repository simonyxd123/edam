# 架构收敛：Flyway 独占 schema 管理 + 一键重置脚本

- 日期：2026-10-22
- 现象：用户重新运行仍报同一错误
- 日志：`G:\data\project\edamlog\nohup.out`

---

## 先说结论：日志没更新

用户给的 `nohup.out` 与上一次**完全相同**（42106 字节，时间戳 03:07 = 北京时间 16:07），
而当前时间是 10:00 —— **这是 7 小时前的旧日志，没有新的运行记录**。

日志里也看不到上一轮加的 `FlywayMigrationGuard`（守卫会在迁移开始前扫描 classpath），
说明**服务器上的代码还没 pull 到最新**（`/var/data/project/edam/`）。

不过顺着用户的启动流程查下去，发现了**第三个**污染源，也是架构级的。

---

## 污染源三：MySQL 的 `docker-entrypoint-initdb.d` 与 Flyway 打架

`dev/docker-compose.yml` 原来挂了两条：

```yaml
volumes:
  - mysql_data:/var/lib/mysql
  - ./db/migration:/docker-entrypoint-initdb.d/01-migration:ro   # ← 裸执行 SQL
  - ./db/seed:/docker-entrypoint-initdb.d/02-seed:ro             # ← 裸执行 SQL
```

`docker-entrypoint-initdb.d` 的机制是：**容器首次启动（数据卷为空）时，
把这些目录里的 .sql 逐个裸执行**。关键点：

- **不经过 Flyway** → **不会写 `flyway_schema_history`**
- 数据卷持久化 → 之后重启不再执行，但表已经建好了

于是后端启动时 Flyway 看到的是：

```
Schema history table `edam`.`flyway_schema_history` does not exist yet
Current version of schema `edam`: << Empty Schema >>
```

→ 判定为"空库"，**从 V1 开始重跑全部迁移** → 而表是 initdb.d 建的、
`CREATE TABLE IF NOT EXISTS` 又静默跳过 → 冲突 → Error 3780。

这解释了日志里的一个关键细节：**history 表不存在，但业务表都存在**。

---

## 收敛：Flyway 成为 schema 的唯一管理者

### 1. compose 去掉 initdb.d 挂载

```yaml
volumes:
  - mysql_data:/var/lib/mysql
  # 故意不挂 docker-entrypoint-initdb.d ——
  # initdb.d 是裸执行 SQL，不写 flyway_schema_history，
  # 会让 Flyway 误判"空库"并从 V1 重跑 → Error 3780
```

schema 与字典数据**全部由 Flyway 管**。

### 2. 基础数据各归其位

| 内容 | 原来 | 现在 |
|---|---|---|
| 部门（7 个） | initdb.d 裸 SQL | 迁移 `V20260830_3000__seed_org_data.sql` |
| 预置角色 + 45 条权限 + 权限分配 | initdb.d 裸 SQL | 迁移 `V20260830_1000__rbac_seed.sql`（已在） |
| 初始管理员账号 | initdb.d 裸 SQL（**且 hash 是假的，登录不了**） | `AdminBootstrap` 启动时按环境创建 |
| k6 压测用户（100 个） | initdb.d 裸 SQL | `dev/db/seed/` 手工执行（不进迁移链） |

### 3. `AdminBootstrap` 区分 dev / production

- **dev profile**：没配密码就用 `admin123`，方便本地起服务
- **production profile**：不配 `edam.bootstrap.admin-password` 就**一个账号都不建**（fail-closed）

初始管理员的密码 hash 绝不进 Git（进迁移历史 = 密码泄露）。

### 4. 删除 `dev/db/seed/V20260812_2000__seed_baseline.sql`

内容已被上面的迁移 + AdminBootstrap 覆盖，且它插的 admin 用的是
**伪造的 bcrypt hash**（`$2a$12$LQv3c1yqBwEHxv6jO5b9WeFNa7qZf7xV8p9Q.5F5xQ5xQ5xQ5xQ5xQ`
—— 长度对但不是真实 hash），那个账号根本登录不了。

### 5. CI 的 flyway locations 只含迁移目录

seed 不进迁移链（压测账号不该出现在任何环境的迁移历史里）。

---

## 一键重置脚本

`scripts/dev-reset.sh` 把三个污染源的清理串成一步：

```bash
./scripts/dev-reset.sh              # 重置 DB + clean + 启动后端
./scripts/dev-reset.sh --no-db      # 只 clean + 启动（保留现有数据）
./scripts/dev-reset.sh --no-start   # 只重置，不启动
```

流程：`git pull` → `rm -rf backend/target` → `docker compose down -v && up -d`
→ 等 MySQL 就绪 → `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092`

（`scripts/dev-clean-backend.sh` 保留，处理"只想清 target"的情况。）

---

## 用户侧操作

服务器上的代码是 `/var/data/project/edam/`，**需要先 pull**：

```bash
cd /var/data/project/edam
git pull
./scripts/dev-reset.sh
```

或者手动等价：

```bash
git pull
rm -rf backend/target
cd dev && docker compose down -v && docker compose up -d
cd ../backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092
```

登录账号：工号 `E000001` / 密码 `admin123`（dev profile 自动创建）。

---

## 验证

```
dev/db/_validate_model_schema.py  → 172/172 字段匹配，exit 0
dev/db/_validate_migrations.py   → 迁移链自洽，exit 0
dev/db/_validate_fk_types.py     → 25 条外键类型全兼容，exit 0
dev/docker-compose.yml           → YAML 解析 OK，mysql 只挂 mysql_data
.gitlab-ci.yml                    → YAML 解析 OK，10 jobs
后端 mvn -o compile               → 通过
```

## 遗留提醒

`dev/README.md` 里没有写启动步骤（之前 grep 不到"迁移/flyway"相关内容），
本次新增的脚本与流程还没写进文档。等这轮跑通后我可以补一份
`dev/README.md` 的"首次启动"章节。
