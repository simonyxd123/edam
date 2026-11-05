# 2026-11-05 普通员工角色收敛为「只读」

> 需求：**普通员工只能播放或预览，不能上传、不能删除。**
>
> 方案书对应章节：
> - §5.4 细粒度权限与外发管控 —— RBAC 角色权限划分，最小授权
> - §6.3 密级模型（L1-L4）—— 密级决定「谁能看」，角色决定「能做什么」
> - §7.1 核心数据表 —— `sys_role_permission` 角色权限关联
> - §14.3 STRIDE —— Elevation of Privilege（越权）：普通员工不得写入资产

---

## 一、问题

`employee`（普通员工）角色在预置迁移 `V20260830_1000__rbac_seed.sql` 里拿到了：

```sql
'dashboard:read','video:read','video:upload',
'document:read','document:upload',
'watermark:read','search:read'
```

也就是说 **任何一个普通员工都能往系统里塞文件**（`video:upload` / `document:upload`）。

在一个「防泄密」系统里，这条授权方向是反的：
**谁有权往密级体系里写东西，比谁能读更要紧**。资产入库是受控动作，
密级、责任人、外发链路都从这一步开始生效。

同时前端**完全没有按权限隐藏按钮** —— 上传 / 删除按钮对所有人可见，
点了才由后端 403。普通员工的体验是"按钮在，但用不了"。

---

## 二、角色权限矩阵（改完的样子）

| 角色 | 看 | 播放/预览 | 上传 | 编辑 | 删除 | 分享 | 外发审批 | 管理类 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `admin` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `dept_manager` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 标签 |
| **`employee`** | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `auditor` | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | 审计导出 |

`employee` 的**完整**权限码（10 条，全是 `action = 'read'`）：

```
dashboard:read  video:read  document:read  watermark:read  notification:read
tag:read  search:read  playback:read  preview:read  classification:read
```

---

## 三、改动清单

### 3.1 后端

**新增 `backend/src/main/java/com/example/edam/security/EmployeePermissions.java`**

普通员工权限基线的**唯一声明处**：角色码 + 只读白名单 + 写动作关键词表。

**新增 `backend/src/main/resources/db/migration/V20261105_1000__employee_readonly_role.sql`**

1. 权限目录补 `watermark:manage`（`WatermarkController` 一直有
   `DELETE /watermarks/cache/{id}`，但目录里根本没有对应的 manage 类权限码 ——
   端点等于对所有登录用户敞开）。
2. employee：**先删**不在白名单里的全部授权，**再补**白名单里缺的。
3. dept_manager：补 `video:delete` / `document:delete` / `watermark:manage`。
4. auditor：不动（本来就是「只读 + 审计导出」）。

> ⚠️ **没有改 `V20260830_1000__rbac_seed.sql`**。
> Flyway 的 `validate-on-migrate: true` 会校验已应用迁移的 checksum，
> 改动老迁移会让**所有已部署的环境启动失败**。新增迁移是唯一正确的做法。

**给端点补权限门**

| 端点 | 之前 | 现在 |
| --- | --- | --- |
| `POST /playback/{id}/token` | 只靠登录 | `video:read` |
| `GET /preview/{id}` | 只靠登录 | `document:read` |
| `GET /documents/{id}/preview` | 只靠登录 | `document:read` |
| `POST /watermarks/extract` | 只靠登录 | `watermark:read` |
| `DELETE /watermarks/cache/{id}` | 只靠登录 | `watermark:manage` |
| `GET/POST/DELETE /webhooks/**` | 只靠登录 | `webhook:read` / `webhook:manage` |
| `GET/POST /distribution/approvals/**` | 只靠登录 | `distribution:read`（审批另有 `distribution:approve`） |

### 3.2 前端

- **`v-permission` 指令：从「删元素」改成「隐藏」**
  （`display:none` + `data-permission-denied` 标记）。
  删元素会和 Vue 的虚拟 DOM 打架 —— vnode 还指着那个节点，
  后续 patch / unmount 会去操作一个已经不在 DOM 里的节点。
  同时支持数组语义（任一命中即可）。
- `Videos.vue`：上传按钮 `v-permission="'video:upload'"`、删除按钮 `v-permission="'video:delete'"`。
- `Documents.vue`：上传按钮 `v-permission="'document:upload'"`、删除按钮 `v-permission="'document:delete'"`、外发按钮 `v-permission="'distribution:read'"`。
- **`/settings` 对所有登录用户开放**：原来路由挂着 `system:read`，
  普通员工根本进不去设置页 ⇒ **改不了自己的密码**，
  只能找管理员（普通员工使用手册 §8.2 说他们应该能自己改）。
  现在整页开放，「系统配置」Tab 单独按 `system:read` 收口（组件内 `v-if`，
  因为 `el-tabs` 的 Tab 标题是它自己渲染的，隐藏面板藏不掉标题）。

---

## 四、为什么用**白名单**而不是「把 upload 那两条删掉」

权限目录（`sys_permission`）会随功能增长而变多。
减法只能挡住**已知**的写权限：以后有人新增一个 `xxx:export`、
又在管理界面上把它勾给了 employee，普通员工就会**默默地**多出一项写权限 ——
这正是防泄密系统最不能接受的漂移。

白名单的语义是「**没显式列出来的，一律没有**」：
新权限默认不给 employee，要给必须在代码和迁移里各写一遍，
而且两处不一致时测试会红（见 §六）。

---

## 五、顺手堵上的越权口子

这几处和「普通员工不能上传/删除」是同一个问题（写操作对所有登录用户敞开），
本次一并收口：

| 位置 | 原来的问题 | 现在的处理 |
| --- | --- | --- |
| `POST /webhooks` | 任何登录用户都能注册回调（回调 URL 由客户端指定 ⇒ SSRF / 数据外传通道） | `webhook:manage` |
| `DELETE /webhooks/{id}` | 任何登录用户都能注销别人的回调 | `webhook:manage` |
| `DELETE /watermarks/cache/{id}` | 同上，且权限目录里压根没有对应权限码 | `watermark:manage`（目录补录） |
| `GET /distribution/approvals` | 不过滤申请人 ⇒ 全公司的外发申请（外部接收人邮箱、有效期、理由）对任何员工可见 | 没有审批权的人**只能看自己提交的** |
| `GET /distribution/approvals/{id}` | 同上 | 只有申请人本人或审批人可看 |
| `POST /distribution/approvals` | 不校验 `doc_id` 归属 ⇒ 任何人都能对 L4 绝密文档提交外发申请 | 只能对**自己看得见**的文档申请 |

---

## 六、验证

### 6.1 后端

`backend/src/test/java/com/example/edam/security/EmployeePermissionsTest.java`（7 个用例）

把三件东西钉在一起，任何一处漂移就红：

1. `EmployeePermissions.READ_ONLY`（代码声明的白名单）；
2. 权限目录 `V20260830_1000__rbac_seed.sql`（每个权限码的 `action`）；
3. 迁移 `V20261105_1000__employee_readonly_role.sql`（真正落到库里的授权）。

用例覆盖：

- 白名单里的每个权限码都在目录里存在；
- 白名单里的每个权限码 `action` 都是 `read`；
- 白名单里不含 `video:upload` / `document:upload` / `video:delete` / `document:delete` / `video:share` / `document:share`；
- 迁移的 `NOT IN` 块 == `IN` 块 == 代码白名单；
- 迁移给 dept_manager 补了删除权，且**没有**顺手给审计导出 / 备份；
- 用反射钉住端点侧：上传 / 删除端点有正确的 `@PreAuthorize`，
  播放 / 预览端点有对应的只读权限（**白名单 + 端点校验，缺一不可**）。

**这个测试有牙齿**（不是摆设）：往白名单里塞一条 `video:upload`，
立刻 3 个用例失败（`everyAllowedCodeIsReadOnly` / `writePermissionsAreNotAllowed` /
`migrationMatchesWhitelist`），失败信息直接指出"哪个权限码的 action 不是 read"。
验证完已还原。

全量后端测试：**154 + 7 = 161 个用例，0 失败**
（唯一的 6 个 error 是既有的 `ApplicationStartupTest`「Communications link failure」——
需要真实 MySQL，本机 3306/3307 没开，与本次改动无关）。

### 6.2 前端

`web/src/utils/permission.spec.ts`（5 个用例）

- 没权限 → 隐藏，且**元素仍在 DOM 里**（钉住"不许删元素"这条）；
- 有权限 → 正常显示；
- 数组语义 = 任一命中即可；
- 支持通配权限码（`*:*` 与 `*:action`）；
- **普通员工白名单下，上传 / 删除按钮全部隐藏，播放 / 预览照常。**

全量前端测试：**58 个用例全绿**（原有 53 + 新增 5）。

---

## 七、上线须知

- **只改后端 + 前端，无表结构变更**，但**有一条数据迁移**
  （`V20261105_1000__employee_readonly_role.sql`）会自动执行。
- **普通员工需要重新登录**：JWT 里带着登录时签发的权限码，
  权限收窄后旧 token 里的 `video:upload` 还在，直到 token 过期或重新登录。
- 部署后抽查：

```sql
SELECT r.code AS role_code, p.code AS perm_code, p.action
FROM sys_role_permission rp
JOIN sys_role r       ON r.id = rp.role_id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE r.code = 'employee' ORDER BY p.code;
```

期望：**10 行，`action` 全是 `read`**。

---

## 八、需要同步的文档（**重要**）

`doc/2026-09-03-普通员工使用手册.pdf`（v3.2）与本次策略**直接冲突**：

| 手册位置 | 原文 | 与新策略 |
| --- | --- | --- |
| §1 核心 4 件事 | 「2. **上传** 自己的工作视频/文档」 | ❌ 普通员工不再能上传 |
| §5.2 上传视频 | 完整的上传操作说明 | ❌ 该章节对普通员工不适用 |
| §6.2 上传文档 | 完整的上传操作说明 | ❌ 该章节对普通员工不适用 |
| §3 界面导览 | 「普通员工通常看到以上 5 个菜单」 | ⚠️ 现在是 6 个（多了「设置」，因为开放了改密码） |
| §9 常见问题 | 「Q: 误删除文档/视频能找回吗？」 | ⚠️ 普通员工不再有删除入口 |

**PDF 需要重做**。手册源文件不在仓库里（`doc/` 下只有 PDF 和图），
本次没有改动它 —— 需要有人拿到源文件重新导出，或按上面这张对照表逐条修订。

另外，本次新加了 `watermark:manage` 这条权限码，
`doc/database_schema.md` / `doc/openapi.yaml` 里的权限目录清单也需要补上它。
