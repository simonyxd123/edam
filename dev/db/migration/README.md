# ⚠️ 本目录已废弃

数据库迁移脚本的**唯一权威来源**是：

```
backend/src/main/resources/db/migration/
```

后端运行时由 Spring Flyway 读 `classpath:db/migration` 执行；
CI 的 `db:migrate` job 也指向同一目录。

## 为什么搬走了

原来这里有**第二套**迁移（`V2026*` 系列），与 backend 里的
`V1/V2/V3` 并存，两套的表名、列名、版本号体系全都不同：

| | backend 的 V1-V3 | 原 dev/db 的 V2026* |
|---|---|---|
| `sys_user_role` 过期列 | `expires_at` | `expire_at` |
| `sys_permission` | 有 `action`/`description`/`is_system` | 全缺 |
| `sys_role_permission` | 有 `constraint_def`/`created_at` | 全缺 |
| `mime_type` 长度 | 已拓宽到 255 | 还是 64（Excel MIME 66 字符会截断） |

任何一份先跑、另一份后跑都会因 `validate-on-migrate` 失败；
而且 Java model（`SysUserRole.expiresAt` 等）只跟其中一套对得上。

现在统一为：**dev/db 的表结构（35 张表的超集）+ backend 的 RBAC 数据**。

## 回滚脚本

`dev/db/rollback/` 下的 `U*.sql` 是**手工**回滚脚本，不参与 Flyway
自动执行（Flyway Community 不支持 undo migration，`U` 前缀文件
不会被自动跑）。用之前先看文件头的警告。
