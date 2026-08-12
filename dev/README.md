# 本地开发环境

本目录包含企业全格式数字资产防泄密系统的本地开发与测试环境。

## 快速开始

```bash
# 1. 启动所有依赖
cd dev/
docker-compose up -d

# 2. 查看服务状态
docker-compose ps

# 3. 查看日志
docker-compose logs -f mysql

# 4. 停止所有服务
docker-compose down

# 5. 停止并清理数据卷
docker-compose down -v
```

## 服务清单

| 服务 | 端口 | 用途 | 默认账号 |
| --- | --- | --- | --- |
| MySQL | 3306 | 业务数据库 | root/rootpass, edam/edampass |
| Redis | 6379 | 缓存 | 无 |
| MinIO | 9000/9001 | 对象存储 | minioadmin/minioadmin |
| RabbitMQ | 5672/15672 | 消息队列 | edam/edampass |
| Vault | 8200 | 密钥管理 | root |
| Elasticsearch | 9200 | 全文搜索 | 无 |
| Prometheus | 9090 | 指标 | 无 |
| Grafana | 3000 | 监控面板 | admin/admin |
| Prism Mock | 4010 | API Mock | 无 |

## 访问地址

- **MinIO 控制台**：http://localhost:9001
- **RabbitMQ 管理**：http://localhost:15672
- **Vault UI**：http://localhost:8200/ui（开发模式）
- **Grafana**：http://localhost:3000
- **Prometheus**：http://localhost:9090
- **Prism Mock API**：http://localhost:4010

## 数据库初始化

MySQL 容器启动时会自动执行：
- `db/migration/V20260812_1000__init_schema.sql`：26 张核心表
- `db/seed/V20260812_2000__seed_baseline.sql`：部门/权限/角色/管理员种子数据

## 测试连接

```bash
# MySQL
docker-compose exec mysql mysql -uedam -pedampass edam

# Redis
docker-compose exec redis redis-cli ping

# MinIO（使用 mc 客户端）
docker-compose exec minio-init mc alias set local http://minio:9000 minioadmin minioadmin
docker-compose exec minio-init mc ls local/

# Vault
docker-compose exec vault vault status
```

## 常见问题

### 端口冲突

如果端口被占用，修改 `docker-compose.yml` 中对应服务的 `ports` 映射。

### 数据持久化

所有数据存储在 Docker 命名卷中。清理：
```bash
docker-compose down -v  # 注意：这会删除所有数据
```

### 性能调优

MySQL 默认 `innodb_buffer_pool_size=2G`，Redis 默认 `maxmemory=1gb`。根据本机配置调整 `docker-compose.yml` 中的 `command` 参数。