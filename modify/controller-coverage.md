# 后端 Controller 端点覆盖报告
- 生成日期：2026-08-12
- 对账对象：`backend/src/main/java/com/example/edam/controller/`（9 个 Controller）vs `doc/openapi.yaml`
- 扫描方式：正则匹配 `@RestController` / `@RequestMapping` / `@*Mapping` 注解

---

## 一、总览

| 指标 | 数值 |
| --- | --- |
| OpenAPI 端点总数 | 65 |
| Controller 实现端点数 | 66 |
| 已覆盖 | 65 (100.0%) |
| ⚠️ 未实现（OpenAPI 缺失） | **0** |
| 多余（实现但 OpenAPI 无） | 1 |

**覆盖率 100%** — 所有 OpenAPI 端点均有 Controller 实现。

## 二、未实现端点（按 tag 分组）

无缺失。

## 三、多余端点（实现但 OpenAPI 未定义）

| 方法 | 路径 | 实现位置 |
| --- | --- | --- |
| `GET` | `/documents/search` | `DocumentController.java` |

## 四、已覆盖端点清单

共 65 个端点，详见 `controller-coverage-covered.json` 备份文件。

## 五、优先级建议

## 六、预估工作量

- 后端 Controller 实现：0 个端点 × 平均 0.5 人天 ≈ **0 人天**
- 含单元测试 + 集成测试：约 **0 人天**
- 按 1 个工程师独立开发：约 **0.0 周**（每周 5 端点）
- 按 2 个工程师并行：约 **0.0 周**

## 七、v3.2 路线图建议

1. **优先级 1**：先补全 `auth`/`health`/`videos`/`documents`/`playback` 这 5 个核心 tag
2. **优先级 2**：补全 `permissions`/`distribution`/`audit`/`users`/`watermarks` 5 个常用 tag
3. **优先级 3**：扩展 `tags`/`notifications`/`webhooks`/`search`/`preview`/`admin` 6 个扩展 tag
4. **统一规范**：每个 Controller 必须 @Tag 一致；Swagger 注解与 OpenAPI tag 一一对应

---

**报告结束。**
