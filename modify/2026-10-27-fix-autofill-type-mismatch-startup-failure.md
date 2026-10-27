# 自动填充按类型匹配：修掉"全新库起不来"

- 日期：2026-10-27
- 触发：服务器上 `git pull && ./scripts/dev-reset.sh` 失败，后端起不来：
  ```
  Column 'granted_at' cannot be null
  java.lang.IllegalStateException: dev profile 下初始管理员创建失败，服务无法登录
  ```

---

## 一、现象

服务器全新库（`dev-reset.sh` 重建 MySQL）后启动，后端**起不来**，
`backend.log` 里：

```
Preparing: INSERT INTO sys_user_role ( user_id, role_id, granted_at, granted_by ) VALUES ( ?, ?, ?, ? )
Parameters: 1(Long), 1(Long), null, 1(Long)      ← granted_at 是 null
ERROR c.e.edam.bootstrap.AdminBootstrap - admin_bootstrap_failed —— 初始管理员账号未创建。
org.springframework.dao.DataIntegrityViolationException:
  Column 'granted_at' cannot be null
```

`AdminBootstrap` 自举时要给 admin 账号绑 admin 角色，插入 `sys_user_role`。
`granted_at` 是 `DATETIME(3) NOT NULL`，插 NULL 直接违反约束 ⇒
自举抛异常 ⇒ dev profile 下 fail fast ⇒ **整个后端起不来**。

同一个 INSERT 里，`created_at` / `updated_at`（`sys_user`，OffsetDateTime）
被正常填上了 —— 差别只在**字段类型**。

---

## 二、根因：自动填充是"按类型精确匹配"的

`MyMetaObjectHandler` 原来用 MP 的 `strictInsertFill` 填充。它要求
**属性名和属性类型都完全相等**才填（反编译 `MetaObjectHandler`
的匹配谓词）：

```java
fieldInfo.getProperty().equals(fieldName)          // 属性名
  && fieldType.equals(fieldInfo.getPropertyType())  // 属性类型 —— 精确相等
  && fieldInfo.isWithInsertFill();
```

而老 handler 把类型**写死**成 `OffsetDateTime.class`：

```java
strictInsertFill(metaObject, field, OffsetDateTime.class, now);
```

项目里的时间字段其实有**三种**类型：

| 实体 | 字段 | 类型 | 之前 |
|---|---|---|---|
| `SysUser` | `createdAt` / `updatedAt` | OffsetDateTime | ✅ 填上了 |
| `SysUserRole` | `grantedAt` | **LocalDateTime** | ❌ 静默跳过 |
| `SysRolePermission` | `createdAt` | **LocalDateTime** | ❌ 静默跳过 |
| `SysPermission` | `createdAt` | **LocalDateTime** | ❌ 静默跳过 |
| `WebhookDelivery` | `createdAt` | **Instant** | ❌ 静默跳过 |

类型对不上 ⇒ **不报错、不填值**，字段留 null。

再叠加 MP 的一个特性，这事就必然炸：对标了 `fill` 的字段，生成 INSERT /
UPDATE 语句时是**无条件**带上该列的（`TableFieldInfo#getInsertSqlColumnMaybeIf`
与 `#getSqlSet`：`withInsertFill` / `withUpdateFill` 为真时直接返回，
不加 `<if>` 判空）。所以 **DB 侧那个 `DEFAULT CURRENT_TIMESTAMP(3)` 根本没机会生效** ——
值只能由 handler 提供，handler 没填上就一定是 NULL。

> 顺带一提：种子迁移 `V20260830_1000__rbac_seed.sql` 里给 admin 绑角色的
> 那条 INSERT 走的是裸 SQL，DB 默认值能生效 —— 所以**迁移后不重启**看不出问题，
> 一旦走 Java 侧插入就炸。这也是"全新库 + 自举"才复现的原因。

### 实测复现（真 MyBatis-Plus 插入路径，H2 落库）

同一个 INSERT、同一张 NOT NULL 表，只换 handler：

| handler | 结果 |
|---|---|
| 老的（类型写死 `OffsetDateTime`） | `INSERT FAILED` → `NULL not allowed for column "granted_at"` |
| 新的（按声明类型填） | `INSERT OK`，`granted_at = 2026-10-27T07:41:41.426` |

老的失败信息与服务器日志里的 `Column 'granted_at' cannot be null` 完全对应。

---

## 三、修复

### 1）按字段**声明类型**填，而不是猜一个类型

`MyMetaObjectHandler` 改成：遍历实体字段 → 读它自己的
`@TableField(fill=...)` → 按**该字段声明的类型**给出对应的时间值。

支持的类型：`OffsetDateTime` / `LocalDateTime` / `Instant` /
`java.util.Date` / `java.sql.Timestamp`。

这样就不再依赖"写死一种类型"这种隐式约定 —— 新增实体、新增时间字段
不需要改 handler。

### 2）填不上必须**喊出来**，不能再静默

老实现里"类型不匹配 ⇒ 跳过"是完全静默的 —— 这正是这个 bug 拖到线上
才发现的原因。现在两种填不上的情况都打 `error` 日志：

- 字段找不到对应的 getter/setter
- 字段类型不在支持列表里

宁可启动时吵一句，也不要等到插入时炸。

### 3）INSERT 与 UPDATE 的语义分开

- **INSERT**：只在字段为 null 时填（已显式赋值的不覆盖）
- **UPDATE**：每次都重填

UPDATE 必须重填，是因为 MP 对 `withUpdateFill` 字段的 SET 子句也是
**无条件**带上该列的 —— 不刷新就会把旧值写回去，`updated_at` 被冻结住。
（`sys_user.updated_at`、`video_resource.updated_at` 都受影响。）

### 4）时间口径统一到 UTC

`LocalDateTime` **没有时区信息**，Connector/J 是**原样写入**的；
而 `OffsetDateTime` / `Instant` 会被换算到 UTC 再落库。
MySQL 侧 `--default-time-zone=+00:00`、JDBC 侧 `serverTimezone=UTC`，
列的默认值 `DEFAULT CURRENT_TIMESTAMP(3)` 也是 UTC 墙钟。

所以若 handler 写 `LocalDateTime.now()`（JVM 本地时区，服务器在 +08:00
就是差 8 小时），同一列里"DB 默认值"和"应用写入值"会打架，跨列比较直接失真。
新增 `TimeUtil`，统一给 UTC 墙钟，并把散落各处的 `LocalDateTime.now()`
一并换掉（`RbacController` / `DataClassificationService` /
`WebAuthn*` / `LeakDetectionAlertService` 共 8 处）。

---

## 四、顺带修掉的另外两个"定时炸弹"

同一个根因，还藏着两处**必炸**的插入路径（都是 NOT NULL 的时间列）：

| 位置 | 表 | 字段 | 触发时机 |
|---|---|---|---|
| `RbacController:203` | `sys_role_permission` | `created_at` | 给角色分配权限 |
| `WebhookDeliveryService:101` | `webhook_delivery` | `created_at` | 记录 webhook 投递 |

这两处之前只要被调用就必然抛 `DataIntegrityConstraintViolationException`。
现在一并修好。

> 补充说明：`sys_permission.created_at` 的**模型字段**（`SysPermission.createdAt`）
> 同样是 `LocalDateTime` + `fill=INSERT`，也受同一个类型不匹配影响；
> 但它今天**没有 Java 侧插入路径** —— 权限目录由迁移脚本
> `V20260830_1000__rbac_seed.sql` 用裸 SQL 灌种子，DB 默认值能生效。
> 所以它属于"模型层面的隐患"而非"线上正在炸的路径"。


---

## 五、验证

- `MyMetaObjectHandlerTest`（新增 11 个用例，全绿）
  - `LocalDateTime` / `Instant` / `OffsetDateTime` 三种类型都能填
  - 已显式赋值的不被覆盖
  - 没标 `fill` 的字段不被填
  - 填进去的值是 UTC
  - UPDATE 会刷新 `updated_at`，且不碰 INSERT-only 的字段
  - 实体被包在 `et` Map 里也能填（MP 的 `@Param(Constants.ENTITY)` 约定）
- `ApplicationStartupTest` 新增 `filledTimestampColumnsAreNotNull`：
  在**真 MySQL**（CI 的 `backend:test` job 提供）上不设 `grantedAt`
  直接插入，断言该列非空 —— 这条用例在修复前必然红。
- 后端全量测试：56 个用例，**只有 `ApplicationStartupTest` 的 3 个红**，
  原因是本机没有 MySQL（`Communications link failure`）；
  已用 `git stash` 在**未修改的原始代码**上复跑确认同样红 —— 与本次改动无关。
- 用真 MyBatis-Plus 插入路径（`BaseMapper.insert` → `MybatisParameterHandler`
  → `MetaObjectHandler`）+ H2 落库做端到端验证：老 handler 必炸，新 handler 通过（见上表）。

---

## 六、遗留

- `ClassificationEnforcement` 里两处 `video.setUpdatedAt(OffsetDateTime.now())`
  现在被 handler 接管了，但两处写的都是**本地时区**的瞬时（瞬时本身没错，
  落库时 Connector/J 会换算到 UTC），值与 handler 写的一致，可以后续删掉
  这两行冗余赋值 —— 本次未动，避免混入无关改动。
