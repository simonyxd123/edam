# 后端 Controller 端点覆盖报告
- 生成日期：2026-08-12
- 对账对象：`backend/src/main/java/com/example/edam/controller/`（9 个 Controller）vs `doc/openapi.yaml`
- 扫描方式：正则匹配 `@RestController` / `@RequestMapping` / `@*Mapping` 注解

---

## 一、总览

| 指标 | 数值 |
| --- | --- |
| OpenAPI 端点总数 | 65 |
| Controller 实现端点数 | 29 |
| 已覆盖 | 28 (43.1%) |
| ⚠️ 未实现（OpenAPI 缺失） | **37** |
| 多余（实现但 OpenAPI 无） | 1 |

**覆盖率 43.1%** — 还有 37 个 OpenAPI 端点未实现 Controller。

## 二、未实现端点（按 tag 分组）

### admin（3 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/admin/backups` | 备份列表 |
| `POST` | `/admin/backups` | 触发备份 |
| `POST` | `/admin/backups/{backup_id}/restore` | 从备份恢复（高危） |

### audit（1 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/audit/logs` | 操作日志查询 |

### auth（1 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/auth/me` | 获取当前用户信息 |

### distribution（2 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/distribution/approvals` | 外发审批列表 |
| `POST` | `/distribution/approvals` | 发起外发审批 |

### documents（2 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/documents` | 文档资源列表 |
| `POST` | `/documents` | 上传文档 |

### health（1 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | 总体健康检查（兼容旧版） |

### notifications（6 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/ws/notifications` | WebSocket 长连接（实时通知） |
| `GET` | `/notifications` | 当前用户通知列表 |
| `POST` | `/notifications/{notif_id}/read` | 标记已读 |
| `POST` | `/notifications/read-all` | 全部标记已读 |
| `GET` | `/notifications/preferences` | 获取通知偏好 |
| `PUT` | `/notifications/preferences` | 更新通知偏好 |

### permissions（3 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/permissions` | 权限列表 |
| `GET` | `/roles` | 角色列表 |
| `GET` | `/users/{user_id}/permissions` | 用户的有效权限（含 RBAC + 资源 ACL） |

### preview（2 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/preview/{doc_id}` | 文档预览（含 Canvas 动态明水印） |
| `GET` | `/preview/{doc_id}/download` | 下载已加密文档 |

### search（2 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/search/videos` | 视频全文检索 |
| `GET` | `/search/documents` | 文档全文检索 |

### tags（6 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/tags` | 标签列表 |
| `POST` | `/tags` | 创建标签 |
| `GET` | `/tags/{tag_id}` | 标签详情 |
| `DELETE` | `/tags/{tag_id}` | 删除标签 |
| `POST` | `/videos/{video_id}/tags` | 给视频添加标签 |
| `DELETE` | `/videos/{video_id}/tags` | 从视频移除标签 |

### users（2 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/users` | 用户列表 |
| `POST` | `/users` | 创建用户 |

### videos（2 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/videos` | 视频资源列表 |
| `POST` | `/videos` | 上传视频 |

### webhooks（4 缺失）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/webhooks` | 当前用户注册的回调列表 |
| `POST` | `/webhooks` | 注册回调 |
| `DELETE` | `/webhooks/{webhook_id}` | 注销回调 |
| `GET` | `/webhooks/{webhook_id}/deliveries` | 回调投递历史 |

## 三、多余端点（实现但 OpenAPI 未定义）

| 方法 | 路径 | 实现位置 |
| --- | --- | --- |
| `GET` | `/documents/search` | `DocumentController.java` |

## 四、已覆盖端点清单

共 28 个端点，详见 `controller-coverage-covered.json` 备份文件。

## 五、优先级建议

### 🔴 P0（影响核心业务流程）

- **auth**：1 个端点
- **videos**：2 个端点
- **documents**：2 个端点
- **health**：1 个端点

### 🟡 P1（影响实施完整性）

- **users**：2 个端点
- **permissions**：3 个端点
- **distribution**：2 个端点
- **audit**：1 个端点

### 🟢 P2（管理后台/扩展功能）

- **preview**：2 个端点
- **notifications**：6 个端点
- **search**：2 个端点
- **tags**：6 个端点
- **webhooks**：4 个端点
- **admin**：3 个端点

## 六、预估工作量

- 后端 Controller 实现：37 个端点 × 平均 0.5 人天 ≈ **18 人天**
- 含单元测试 + 集成测试：约 **37 人天**
- 按 1 个工程师独立开发：约 **7.4 周**（每周 5 端点）
- 按 2 个工程师并行：约 **3.7 周**

## 七、v3.2 路线图建议

1. **优先级 1**：先补全 `auth`/`health`/`videos`/`documents`/`playback` 这 5 个核心 tag
2. **优先级 2**：补全 `permissions`/`distribution`/`audit`/`users`/`watermarks` 5 个常用 tag
3. **优先级 3**：扩展 `tags`/`notifications`/`webhooks`/`search`/`preview`/`admin` 6 个扩展 tag
4. **统一规范**：每个 Controller 必须 @Tag 一致；Swagger 注解与 OpenAPI tag 一一对应

---

**报告结束。**
