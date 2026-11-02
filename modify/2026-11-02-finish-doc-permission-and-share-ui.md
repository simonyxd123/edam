# 补完遗留：文档侧的授权模型 + 前端分享界面

- 日期：2026-11-02
- 接上一轮（`2026-11-01-fix-nginx-forwarded-host-and-video-permission.md`）的遗留清单

---

## 一、`doc_permission` 落地（上一轮只做了视频侧）

### 模型

与视频侧同构，**多一个 `print` 位**（`doc/database_schema.md` §3.2）：

| 位 | 含义 |
|---|---|
| 1 | view（能预览） |
| 2 | download（能下原文） |
| 4 | edit |
| 8 | delete |
| 16 | distribute（能再分享） |
| **32** | **print**（方案书 5.4「禁止打印」） |

可见性规则与视频侧完全一致（方案书 6.3.1）：L1/L2 全体登录用户可见；
L3 上传者+同部门+被授权；L4 上传者+被授权。

### 一条**与视频侧不同**的规则：下载权 ≠ 可见性

方案书 5.6.1【例外】：

> 如需下载：必须走文档外发审批（5.4 节）… 【例外】L1 公开资源允许下载；L2-L4 默认禁止

⇒ **L2 文档是"能预览但不能下载"**。所以下载**不能**沿用可见性判定。

这也是我把 `DocumentPreviewService.load` 的 `boolean withWatermark` 换成
`Access.VIEW / Access.DOWNLOAD` 枚举的原因 —— 用布尔表达就分不清
"预览"和"下载"了，而这两个要**不同的授权位**。

### 接入点

| 位置 | 校验 |
|---|---|
| `DocumentService.list` | 可见性过滤（与 `canView` 同源） |
| `DocumentController.getById` | 可见性 → 403 |
| `DocumentPreviewService.load(VIEW)` | 可见性 → 403 |
| `DocumentPreviewService.load(DOWNLOAD)` | 下载权 → 403 |

### 顺带修正上一轮对"外发审批"的表述

上一轮我写"视频的分享目前不走外发审批流程（文档的走）"。
核对方案书 5.4 后确认：**`distribution_approval` 表是 `doc_id` 专属**，
且记录的是"外部接收人"（`external_recipient_name/email/org`）——
它是**文档发给外部人员**的审批流，与"内部授权给同事"是两件事。
所以视频侧没有外发审批**不是遗漏**，是表结构本来就不支持。

---

## 二、发现并修掉的一个真 bug：请求体字段名不匹配契约

`batch-permission` 的请求体按 `openapi.yaml` 是 snake_case
（`video_id` / `user_ids` / `actions` / `expire_at`），
但 DTO 用的是 camelCase 字段名，且**没有** `@JsonProperty` ——
Jackson 找不到 `video_id` ⇒ `userIds` 为 null ⇒ 接口一律 400。

项目里其它请求 DTO（如 `VideoStatusUpdateRequest`）都用
`@JsonProperty("snake_case")`，这两个 DTO 漏了。已补上。

**这是靠 MockMvc 测试发现的** —— 直接调 controller 方法的测试**测不出来**
（Jackson 的绑定发生在 HTTP 层，方法调用时根本没经过反序列化）。
`VideoPermissionControllerTest` / `DocPermissionControllerTest` 走真实的
MockMvc 链路，把字段名问题暴露出来了。

顺带验掉一个我不确定的点：**DELETE 带请求体**（撤销授权要用 body 传
`user_ids` 数组，query string 放不下）。RFC 9110 允许 DELETE 有请求体，
Spring 的 `@RequestBody` 也支持，但**没测过就不算数** ——
MockMvc 用例断言了 `user_ids` 真的从 body 里解析出来了。

---

## 三、前端分享界面

`batch-permission` 之前只有 API、没有入口。新增：

| 文件 | 作用 |
|---|---|
| `web/src/api/permission.ts` | 授权 API + `ACTION` 位掩码常量 |
| `web/src/components/ShareDialog.vue` | 分享对话框（视频/文档通用） |
| `web/src/views/Videos.vue` | 列表加「分享」按钮（`v-permission="'video:share'"`） |
| `web/src/views/Documents.vue` | 同上（`v-permission="'document:share'"`） |

对话框做的事：选人 → 勾动作位 → 选有效期 → 调 `batch-permission`；
并列出**当前已授权**（含已过期的标红），可单条撤销。

两个细节：
- 已在授权列表里的人**不再出现在下拉里** —— 再次授权是覆盖不是叠加，
  摆出来容易让人以为能叠加。
- 文档比视频多一个「打印」位（`allow-print` 属性控制）。

---

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `model/DocPermission.java`（新） | `doc_permission` 实体 |
| `repository/DocPermissionRepository.java`（新） | 仓储 |
| `service/DocPermissionService.java`（新） | 授权模型判定（`grant/revoke/canView/canDownload/canDistribute/applyVisibility`） |
| `controller/DocumentController.java` | `batch-permission` 三个方法；详情加可见性；DTO 补 `@JsonProperty` |
| `controller/VideoController.java` | DTO 补 `@JsonProperty`（上一轮漏了） |
| `controller/PreviewController.java` | `load` 改用 `Access` 枚举 |
| `service/DocumentPreviewService.java` | `load(docId, userId, Access)`；按访问方式鉴权 |
| `service/DocumentService.java` | 列表加可见性过滤 |
| `web/src/api/permission.ts`（新） | 授权 API + 位掩码常量 |
| `web/src/components/ShareDialog.vue`（新） | 分享对话框 |
| `web/src/api/client.ts` | 新增 `deleteWithBody`（DELETE 带 body） |
| `web/src/views/Videos.vue` / `Documents.vue` | 加「分享」按钮 |
| `doc/openapi.yaml` | 文档侧的 batch-permission 契约 + 可见性/下载权说明 |

---

## 五、验证

- backend 全量：**118 个用例，112 passed**，6 个 `ApplicationStartupTest` 报错 ——
  `Communications link failure`，本机无 MySQL（改动前同样报，见上一轮记录）
- 新增用例：
  - `DocPermissionServiceTest` **15 个** —— 可见性 + **下载权与可见性分离** + 有效期 + 动作位 + 落库
  - `VideoPermissionControllerTest` **6 个** / `DocPermissionControllerTest` **4 个** —— MockMvc 走真实 HTTP 层
- web：**41 passed**（新增 7 个：位掩码取值、snake_case body、DELETE 带 body）
- `vue-tsc --noEmit` 无报错；`openapi.yaml` YAML 解析通过（66 个 path）

---

## 六、服务器上怎么收尾

```bash
cd /var/data/project/edam && git pull && sudo systemctl restart edam-backend
```

**数据库不需要 migration** —— `doc_permission` 表在初始 schema 里就有。

⚠️ **存量文档同样没有授权行。** L1/L2 不受影响（对所有登录用户可见），
但 **L3/L4 的文档在被显式授权前谁都看不了**（除上传者本人）。

### 验证

```bash
# 授权接口不再是 501，且字段名能正确绑定
curl -s -X POST http://127.0.0.1:8092/api/v1/documents/batch-permission \
  -H "Authorization: Bearer <上传者token>" -H "Content-Type: application/json" \
  -d '{"doc_id":3,"user_ids":[101],"actions":1,"expire_at":"2026-12-01T00:00:00Z"}'
# 期望：{"granted_count":1,...}
```

---

## 七、遗留

1. **分享界面只覆盖内部授权**（授权给同事）。
   **外发**（发给外部人员）在文档侧已有 `distribution_approval` 流程，
   但 `Documents.vue` 的「外发」按钮仍是 `ElMessage.info('TODO')` ——
   没接审批流。

2. **外发审批的 `doc_permission` 没联动** ——
   `distribution_approval` 审批通过后，是否自动给外部接收人写一行
   `doc_permission`（含 download 位 + 有效期）还没做。

3. **`video_permission` 没有"下载原片"的端点** —— download 位无处可施。
   视频侧目前只有 HLS 播放（方案书 7「不是 DRM」定位），没有原片下载接口。

4. **K8s 侧要重建镜像**（后端代码改了）；前端要重新构建。
