# EDAM 数据库 Seed 目录（dev 环境）

- **用途**：dev / staging 环境的 Flyway 数据库 seed 初始化
- **加载时机**：`docker-entrypoint-initdb.d/02-seed`（MySQL 容器首次启动时按文件名字母顺序执行）
- **加载规范**：
  - 命名 `V{YYYYMMDD}_{HHMM}__{description}.sql`
  - **幂等性**：必须使用 `INSERT IGNORE` 或 `ON DUPLICATE KEY UPDATE`，确保重复执行不报错
  - **不删除既有数据**：禁止 TRUNCATE / DELETE 全表

---

## 一、文件清单

| 文件 | 说明 | 生成方式 |
| --- | --- | --- |
| `V20260812_2000__seed_baseline.sql` | 部门 / 权限 / 角色（4 个）/ 超级管理员 | 手动编写 |
| `V20260829_2000__seed_k6_users.sql` | k6_tester 角色 + 100 个测试用户 | `generate_k6_seed.py` |
| `generate_k6_seed.py` | k6 seed 生成器（Python） | 运行时生成 SQL |

---

## 二、k6 测试用户 Seed（v3.4 V4-03）

### 2.1 用途

为 k6 压测脚本提供 100 个 `TEST_001 ~ TEST_100` 测试用户，统一密码 `TestP@ssw0rd!`。

### 2.2 重新生成 SQL

```bash
# 安装 bcrypt（如未装）
pip install bcrypt

# 生成 SQL（默认 100 用户）
python3 generate_k6_seed.py > V20260829_2000__seed_k6_users.sql

# 生成 500 用户（CI 大规模压测）
python3 generate_k6_seed.py --users 500 > V20260829_2000__seed_k6_users.sql

# 降低 bcrypt cost（dev 加速，prod/staging 用 10+）
python3 generate_k6_seed.py --rounds 4 > V20260829_2000__seed_k6_users.sql
```

### 2.3 手动执行（首次或修复）

dev 环境首次启动会自动加载。如需在已有数据库手动执行：

```bash
# 进入 MySQL 容器
docker exec -it edam-mysql mysql -uroot -p${MYSQL_ROOT_PASSWORD:-rootpass} edam

# 加载 seed
source /docker-entrypoint-initdb.d/02-seed/V20260829_2000__seed_k6_users.sql

# 验证
SELECT * FROM v_k6_seed_status;
-- 期望: role_exists=1, role_perms=7, k6_users=100, k6_role_assigned=100
```

### 2.4 验证查询

```sql
-- 1. 角色是否存在
SELECT * FROM sys_role WHERE id = 5;
-- 期望: id=5, code='k6_tester'

-- 2. 权限是否授予（覆盖 8 大类接口）
SELECT p.code
FROM sys_role_permission rp
JOIN sys_permission p ON rp.permission_id = p.id
WHERE rp.role_id = 5;
-- 期望: 7 行（video:read / video:download / video:distribute /
--         document:read / document:download / document:distribute /
--         system:audit_export）

-- 3. 用户数量
SELECT COUNT(*) AS k6_user_count
FROM sys_user
WHERE employee_no LIKE 'TEST_%';
-- 期望: 100

-- 4. 角色关联
SELECT COUNT(*) AS k6_role_assigned
FROM sys_user_role ur
JOIN sys_user u ON ur.user_id = u.id
WHERE u.employee_no LIKE 'TEST_%'
  AND ur.role_id = 5;
-- 期望: 100

-- 5. 登录验证（随机选一个用户）
SELECT username, employee_no, status, dept_id
FROM sys_user
WHERE employee_no = 'TEST_001';
-- 期望: username='TEST_001', status=1, dept_id=5
```

### 2.5 清理（生产环境严禁）

如需清理 k6 seed（dev 环境偶尔用）：

```sql
-- 1. 删除角色关联
DELETE ur FROM sys_user_role ur
JOIN sys_user u ON ur.user_id = u.id
WHERE u.employee_no LIKE 'TEST_%' AND ur.role_id = 5;

-- 2. 删除用户
DELETE FROM sys_user WHERE employee_no LIKE 'TEST_%';

-- 3. 删除角色权限
DELETE FROM sys_role_permission WHERE role_id = 5;

-- 4. 删除角色
DELETE FROM sys_role WHERE id = 5;

-- 5. 删除验证视图
DROP VIEW IF EXISTS v_k6_seed_status;
```

---

## 三、与 CI / 压测的集成

### 3.1 CI 流水线（GitHub Actions）

```yaml
- name: Seed k6 users
  run: |
    pip install bcrypt
    python3 dev/db/seed/generate_k6_seed.py --users 200 > /tmp/k6_seed.sql
    docker exec -i edam-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD edam < /tmp/k6_seed.sql
```

### 3.2 压测脚本依赖

`perf/k6/lib/config.js` 默认使用 `TEST_001 ~ TEST_100`，密码 `TestP@ssw0rd!`。

可通过环境变量调整：

```bash
K6_USER_POOL_SIZE=200 k6 run perf/k6/scripts/load-test.js
```

---

## 四、安全约束

| 约束 | 说明 |
| --- | --- |
| ⚠️ 仅 dev/staging | `TestP@ssw0rd!` 是固定密码，**生产环境严禁 seed** |
| ⚠️ 角色权限最小化 | k6_tester 只授予 read 类权限，无 admin 类（user_manage / role_manage / settings / backup）|
| ⚠️ 不影响真实用户 | 100 个 TEST_NNN 是新增虚拟用户，不修改任何现有用户 |
| ✅ 幂等 | 全部使用 `INSERT IGNORE`，可重复执行 |
| ✅ 可清理 | 提供完整清理 SQL（4 条 DELETE）|

---

## 五、关联文档

- `perf/k6/README.md` — k6 压测套件使用文档
- `modify/2026-08-29-V4-03-烟囱测试执行指南.md` — 烟囱测试 SOP
- `modify/2026-08-29-V4-03-k6压测执行计划.md` — 完整执行计划
- `modify/2026-08-29-v3.4路线图.md` — V4-03 任务定义