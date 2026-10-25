# 本地开发环境

本目录包含企业全格式数字资产防泄密系统的本地开发与测试环境。

---

## 快速开始

### 首次启动（或数据库被改坏后）

```bash
cd /var/data/project/edam        # 仓库根
git pull
./scripts/dev-reset.sh            # 一键：清 target + 重建 MySQL + 启动后端
```

`dev-reset.sh` 依次做 4 件事，每一件都是为了清掉一种"污染源"：

| 步骤 | 清什么 | 为什么 |
|---|---|---|
| `git pull` | 代码没更新 | 服务器上的迁移脚本可能还是旧的 |
| `rm -rf backend/target` | `target/classes/db/migration/` 里**已删除的旧迁移** | Maven 的 resources 插件只复制/覆盖，**不删除**源里已移除的资源 → Flyway 会同时加载两套 schema → 外键报 `are incompatible`（Error 3780） |
| `docker compose down -v && up -d` | 库里由 `initdb.d` 裸执行 SQL 建的旧表 + 失败的 `flyway_schema_history` 记录 | 裸执行的 SQL 不写 history，Flyway 看到"空库"会从 V1 重跑 |
| `mvn spring-boot:run` | — | Flyway 自动跑完整套迁移（schema 唯一权威来源） |

### 日常启动（数据完好时）

```bash
cd /var/data/project/edam
git pull

# 1. 依赖
cd dev && docker compose up -d && cd ..

# 2. 后端（开发端口 8092）
cd backend
mvn spring-boot:run          # application.yml 已是 8092，不用带参数

# 3. Worker（另开终端）
cd worker/src
python3 -m uvicorn edam_worker.main:app --reload --port 8001

# 4. 前端（另开终端）
cd web && npm install && npm run dev
```

访问：
- 前端 http://192.168.10.2:5173/
- 后端 http://192.168.10.2:8092/api/v1
- 登录：**工号 `E000001` / 密码 `admin123`**（dev profile 自动创建）

### 诊断脚本

启动失败时先跑这个，它会一次性输出全部关键状态并给出结论：

```bash
cd /var/data/project/edam && ./scripts/dev-diagnose.sh
```

它检查：代码是否最新 / target 里有无陈旧迁移 / 库里有几张表 /
`flyway_schema_history` 状态 / FK 相关列是 signed 还是 UNSIGNED。

---

## 服务清单

| 服务 | 端口 | 用途 | 默认账号 |
| --- | --- | --- | --- |
| MySQL | **3307**（容器内 3306） | 业务数据库 | root/rootpass, edam/edampass |
| Redis | 6379 | 缓存 | 无 |
| MinIO | 9000/9001 | 对象存储 | minioadmin/minioadmin |
| RabbitMQ | 5672/15672 | 消息队列 | edam/edampass |
| Vault | 8200 | 密钥管理 | root |
| Elasticsearch | 9200 | 全文搜索 | 无 |
| Prometheus | 9090 | 指标 | 无 |
| Grafana | 3000 | 监控面板 | admin/admin |
| Prism Mock | 4010 | API Mock | 无 |

## 端口对照（跨组件的坑）

| 组件 | 位置 | 端口 |
|---|---|---|
| 后端 | `application.yml` 的 `server.port` | **8092** |
| 后端 | helm `backend.service.port` / `SERVER_PORT` | **8092** |
| 前端 vite dev server | `web/vite.config.ts` | **5173** |
| 前端 vite proxy 目标 | `web/vite.config.ts` | `http://localhost:8092` |
| Worker | `worker/src/edam_worker/config.py` | 8001（HTTP）/ 9100（metrics） |
| Worker 回调后端 | helm `worker.env.BACKEND_BASE_URL` | `http://edam-backend:8092/api/v1` |

> **全链路统一 8092**（application.yml / helm / 前端代理 / worker 回调 /
> Prometheus 抓取 / OpenAPI server url）。
>
> 此前是"application.yml 8080 + 前端代理 8092 + 启动参数再覆盖 8092"三方
> 不一致 —— 改 application.yml 还得记得同步启动参数，漏一次就是前端全 502。
> 现在开发期直接 `mvn spring-boot:run` 就是 8092，不用带任何参数。

---

## 数据库：schema 由 Flyway 独占

**唯一权威来源**：

```
backend/src/main/resources/db/migration/
```

| 文件 | 内容 |
|---|---|
| `V20260812_1000__init_schema.sql` | 35 张表的初始 schema |
| `V20260820_1000__add_soft_del_and_session.sql` | 软删除 + 会话表 |
| `V20260825_1000__add_password_history.sql` | 密码历史 |
| `V20260826_1000__add_webauthn.sql` | WebAuthn 凭据 |
| `V20260827_1000__add_fingerprint_library.sql` | 帧指纹 |
| `V20260828_1000__add_data_classification.sql` | 密级 |
| `V20260830_1000__rbac_seed.sql` | 45 条权限 + 预置角色 + 权限分配 |
| `V20260830_2000__align_model_columns.sql` | 对齐 Java model 的列 |
| `V20260830_3000__seed_org_data.sql` | 部门等字典数据 |
| `V20260830_4000__seed_tags.sql` | 预置标签 |

### ⚠️ MySQL 容器**故意不挂** `docker-entrypoint-initdb.d`

`initdb.d` 是在容器首次启动时**裸执行 SQL**，不经过 Flyway，
因此**不写 `flyway_schema_history`**。结果是：表是 initdb.d 建的、
history 却不存在 → 后端启动时 Flyway 判定"空库"从 V1 重跑 →
`CREATE TABLE IF NOT EXISTS` 静默跳过已有表 → 加外键时报 Error 3780。

**schema 与字典数据现在统一由 Flyway 管理。**

### 改迁移脚本的规矩

1. **已应用过的迁移文件永不修改** —— 改了 checksum 就对不上，
   报 "Detected failed migration" / checksum mismatch。
   要改就追加新版本号。
2. 删了 `src/main/resources` 下的文件 → **必须 `mvn clean`**，
   否则 `target/classes` 里会残留（Flyway 会同时加载两套）。
3. 改完跑静态校验（不需要真库）：

```bash
python dev/db/_validate_model_schema.py   # model 字段 ↔ 运行时 schema
python dev/db/_validate_migrations.py    # 迁移链自洽
python dev/db/_validate_fk_types.py      # 外键两端类型兼容
```

这三个脚本已接进 CI 的 `db:migrate` job。

### 手工重置数据库

```bash
# 方式 A：docker
cd dev && docker compose down -v && docker compose up -d

# 方式 B：直接 mysql
mysql -h127.0.0.1 -uroot -p -e "DROP DATABASE edam;
  CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

---

## 测试连接

```bash
# MySQL
docker compose exec mysql mysql -uedam -pedampass edam

# Redis
docker compose exec redis redis-cli ping

# MinIO（使用 mc 客户端）
docker compose exec minio-init mc alias set local http://minio:9000 minioadmin minioadmin
docker compose exec minio-init mc ls local/

# Vault
docker compose exec vault vault status
```

---

## 常见问题

### 启动报 Flyway 错误

先跑 `./scripts/dev-diagnose.sh` 看结论。两种典型：

| 报错 | 原因 | 解决 |
|---|---|---|
| `Detected failed migration to version 20260812.1000` | 迁移执行到一半失败，history 里有 `success=0` 的行 | `rm -rf backend/target` + 重建库 |
| `Error 3780: ... are incompatible` | 库里混着两套 schema（signed vs UNSIGNED BIGINT） | 重建库 |
| `已废弃的迁移脚本`（自研守卫） | `target/classes` 里有已删除的旧迁移 | `rm -rf backend/target` |

### 端口冲突

`Address already in use`：

```bash
sudo ss -ltnp 'sport = :8001'        # 看谁占着
sudo pkill -f 'edam_worker.main'      # 干掉残留的 worker
```

改 `docker-compose.yml` 里的 `ports` 映射可以换端口。

### 前端 502 / 请求超时

前端 vite proxy 指向 `http://localhost:8092`（见 `web/vite.config.ts`）。
后端必须跑在 **8092**：

```bash
mvn spring-boot:run          # application.yml 已是 8092，不用带参数
```

### 数据持久化

所有数据在 Docker 命名卷中。清理（会删数据）：

```bash
docker compose down -v
```

### 性能调优

MySQL 默认 `innodb_buffer_pool_size=2G`，Redis 默认 `maxmemory=1gb`。
按本机配置调整 `docker-compose.yml` 的 `command` 参数。
