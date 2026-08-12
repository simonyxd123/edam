# 数据库迁移指南

## 文件组织

```
dev/db/
├── migration/                          # Flyway 迁移脚本
│   ├── V20260812_1000__init_schema.sql    # 初始化 26 张表
│   └── U20260812_9000__rollback_init_schema.sql  # 回滚（删除所有表）
└── seed/                                # 种子数据
    └── V20260812_2000__seed_baseline.sql  # 部门/权限/角色/管理员
```

## 命名规范

| 类型 | 格式 | 示例 |
| --- | --- | --- |
| 前向迁移 | `V<version>__<description>.sql` | `V20260812_1000__init_schema.sql` |
| 回滚脚本 | `U<version>__<description>.sql` | `U20260812_9000__rollback_init_schema.sql` |
| 种子数据 | `V<version>__seed_<topic>.sql` | `V20260812_2000__seed_baseline.sql` |
| 修复脚本 | `R__<description>.sql` | （可重复执行的修复） |

## 使用方法

### Docker Compose 环境

```bash
# 启动时自动执行（首次）
cd dev && docker-compose up -d mysql

# 手动执行新迁移
docker-compose exec mysql mysql -uroot -prootpass edam < dev/db/migration/V_xxx__yyy.sql
```

### Flyway CLI

```bash
# 迁移到最新
flyway -url=jdbc:mysql://localhost:3306/edam \
       -user=root -password=xxx \
       -locations=filesystem:dev/db/migration \
       migrate

# 查看状态
flyway -url=jdbc:mysql://localhost:3306/edam \
       -user=root -password=xxx \
       info

# 修复（重新对齐 Flyway 元数据表）
flyway -url=jdbc:mysql://localhost:3306/edam \
       -user=root -password=xxx \
       repair

# 回滚（需要先有 U__xxx.sql）
flyway -url=jdbc:mysql://localhost:3306/edam \
       -user=root -password=xxx \
       -locations=filesystem:dev/db/migration \
       undo
```

### Spring Boot 自动迁移

```yaml
# application.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    validate-on-migrate: true
```

## ⚠️ 回滚注意

**U__xxx.sql 必须谨慎编写**：
1. 仅在测试环境或紧急回滚时使用
2. 生产环境应通过新 V__xxx.sql 修复，不应使用 U
3. Flyway 不支持"事务性回滚"——失败需要手动修复

## 灰度发布（生产大表）

参考 `database_schema.md` 第十章迁移规范：
- 使用 `gh-ost` 或 `pt-online-schema-change` 异步执行
- 先在 staging 完整测试
- 生产低峰期（02:00-06:00）执行
- 监控 ALTER 进度，异常立即停止

## 索引维护

参考 `database_schema.md` 第十四章：
- 每周日 03:00 自动 `ANALYZE TABLE`
- 季度审查：删除 30 天未使用索引
- 慢查询日志：`long_query_time = 1s`

## 备份与恢复

参考 `database_schema.md` 第八章：
- 全量备份：每天 02:00
- 增量 binlog：实时
- MinIO/OSS：每天 03:00
- RPO：5 分钟 / RTO：30 分钟