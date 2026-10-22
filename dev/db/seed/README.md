# 开发环境种子数据（手工执行，不进 Flyway 迁移链）

## 为什么不在迁移链里

- 这些是**环境数据**（k6 压测用的 100 个测试用户），不是 schema
- Flyway 迁移链里放 seed 会让「谁在什么环境建了什么用户」变得不可控
- 生产库不该有压测账号

## 执行方式

```bash
mysql -h<host> -P3307 -u<user> -p edam < dev/db/seed/V20260829_2000__seed_k6_users.sql
```

## 已迁移走的部分

| 原内容 | 现在在哪 |
|---|---|
| 部门（7 个） | `V20260830_3000__seed_org_data.sql`（正式迁移，任何环境都会建） |
| 预置角色（admin / dept_manager / employee / auditor） | `V20260830_1000__rbac_seed.sql`（正式迁移） |
| 初始管理员账号 | `AdminBootstrap`（启动时按环境创建：dev 用 admin123，production 必须显式配 `edam.bootstrap.admin-password`） |
| 45 条权限目录 + 角色权限分配 | `V20260830_1000__rbac_seed.sql`（正式迁移） |
