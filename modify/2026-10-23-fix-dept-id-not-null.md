# 修复 AdminBootstrap 插入 admin 失败导致应用起不来

- 日期：2026-10-23
- 现象：
  ```
  Field 'dept_id' doesn't have a default value
  INSERT INTO sys_user (username, password_hash, employee_no, real_name, email,
                        status, mfa_enabled, failed_login_count, password_changed_at,
                        must_change_password, created_at, updated_at) VALUES (...)
  ```
  应用在 `Started EdamApplication` 之后立刻退出（exit code 1）。
- 日志：`G:\data\project\edamlog\backend.log`

---

## 好消息：迁移链已经全部跑通

```
Successfully applied 10 migrations to schema `edam`, now at version v20260830.4000
```

前几轮的迁移问题（3780 / 1054 ×2）**全部解决**。
这次是**应用代码**的问题，不是 schema 的。

## 根因

`AdminBootstrap` 建初始管理员时**没有设 `dept_id`**，而
`sys_user.dept_id` 是 `BIGINT UNSIGNED NOT NULL` 且**无默认值**
→ MySQL 严格模式直接拒绝插入。

而 Java model 里是 `private Long deptId;` —— **包装类型 = 可空**。
也就是说 **schema 的约束与 model 的语义本来就不一致**，
只是之前没人往 `sys_user` 插过不设 dept_id 的记录，暴露不出来。

## 修复

### 1. 新增迁移 `V20260830_5000__dept_id_nullable.sql`

把 `sys_user.dept_id` 改为可空。

> `V20260812_1000`（init）已经在多个环境成功应用，**不能改**
> （checksum 会对不上），所以用新版本号追加。

为什么放宽而不是在代码里硬塞一个部门 id：
1. model 字段是 `Long`（可空），schema 的 NOT NULL 与之语义不一致
2. "用户尚未分配部门"是合法业务状态（刚批量导入、待审批等）
3. 硬塞部门 id 会依赖 `sys_dept` 的种子数据顺序，在没跑 seed 的
   环境里会写成悬空引用

外键 `fk_user_dept` 不受影响 —— MySQL 的外键允许被引用列为 NULL。

### 2. `AdminBootstrap` 健壮化

- 显式 `admin.setDeptId(null)`（语义清晰，不依赖隐式行为）
- **自举失败不再让整个应用退出**：包 try/catch，失败只记 error 日志。
  之前这里抛异常会直接终止进程（exit 1）—— 管理员得先想办法把服务
  弄起来才能进管理界面建账号，死锁。

## 校验器补第 6 个维度

新增检查：**model 的可空字段（数值包装类型 Long/Integer）对应的列，
在 schema 里却是 NOT NULL 且无 DEFAULT**。

这类不一致是**潜在的插入失败点** —— 任何不设该字段的 INSERT 都会报
`Field 'x' doesn't have a default value`。本次就是它。

实现上做了两点取舍：
- 只看**数值型包装类型**：它们通常是指向别的表的**可选关联**
  （deptId / uploaderId / userId / roleId / tagId …），
  业务上确实存在"这个字段不设"的场景
- 排除 **String / Boolean**：实践中总是会赋值（username / title / name），
  全报出来会把真问题淹没在噪音里
- 排除 **id**（自增主键由 DB 生成，不会成为失败点）

输出是**汇总式**（按表列出列名）而不是逐字段一行，避免刷屏。

### 顺带修：校验器把注释里的示例 SQL 当真

新写的迁移文件里有"说明性"注释段落，里面带示例 SQL
（如 `INSERT INTO sys_user (...) VALUES (...)`）→ 校验器误报
"列 sys_user.... 不存在"。

修复：两个校验器都先 `strip_sql_comments()` 再解析。
这不只是绕过误报 —— 注释里的示例本来就是**给人看的**，
校验器该跟人读的一样。

## 验证

```
后端 compile                OK
DB model↔schema (6 维度)   OK
DB 迁移链 (5 维度)          OK
DB 外键类型                 OK
前端 vue-tsc / test(19)     OK
e2e (19)                    OK
helm × 3 overlay            OK
```

`sys_user.dept_id` 已从"可空性不一致"列表中消失。
