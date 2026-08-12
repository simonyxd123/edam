# 更新日志（Changelog）

本项目所有重要变更记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/) 规范。

## [未发布]

### 计划中
- 视频/文档 API Controller 完整实现（v3.0 全部 51 端点）
- Web 前端：Documents / Watermark / Distribution 页面
- 真实驱动层开发（Windows minifilter 优先）

---

## [3.1.0] - 2026-08-12

### 新增
- **OpenAPI 规范扩展**：从 30 端点扩展到 51 端点；Schema 从 26 扩展到 36
  - 健康检查细化为 4 端点（live/ready/components/health）
  - 新增 WebSocket 实时通知 `/ws/notifications`
  - API 版本管理策略文档化（12 月维护期 + 6 月并行期）
  - 登录限流策略（IP 5/min、工号 3/min、图形验证码、账号锁定）
  - 批量操作（videos/documents batch-delete, batch-permission）
  - 全文搜索（/search/videos, /search/documents）
  - 标签管理（/tags CRUD + /videos/{id}/tags）
  - 通知消费端（/notifications 列表 + 标记已读 + 偏好）
  - 备份与恢复（/admin/backups 含 MFA + 二级审批）
  - Webhook 回调注册与投递历史
  - 关键接口增加 example 字段；Error schema 增加 trace_id
- **ER 文档**：从 9 章扩展到 18 章
  - 数据库迁移规范（Flyway、命名、gh-ost/pt-osc）
  - 时区与精度统一规范（UTC DATETIME(3)、前端 dayjs）
  - 应用层加密策略（Vault Transit、密钥轮转）
  - 软删除与乐观锁规范
  - 索引使用率监控
  - 4 张新表：Notification / Webhook / WebhookDelivery / Backup
- **方案书评审闭环**：3 份评审报告识别问题全部修复
- **drawio 源文件**：4 个 v3.0 修复
  - 修复样式字符串属性重复 bug
  - ER 图关系连线加 1:N 箭头方向（endArrow=ERmany）
  - 时序图生命线虚线规范化（dashPattern=8 8）
  - 改进图例（带色块）

### 修复
- MD5 在示例与术语表的自相矛盾（推荐 Nginx 1.21+ secure_link_md5_sha）
- 移动终端策略缺失（5.1.7 节新增 iOS/Android MDM 方案）
- 驱动失效兜底机制缺失（心跳 + 文档外发网关 + 巡检 Agent 三重）
- 合规告知拒签处理流程缺失（HR + 法务联合处理）
- 工期数字不一致（统一为 35-49 周）
- 人力估算虚高（按"角色 × 阶段投入系数"重算）
- 等保测评价格偏低（调整为 20-30 万）
- Vault 故障降级方案缺失（Raft 集群 + 会话密钥缓存）
- 文档溯源后法律衔接缺失（5.3.5 节新增）

### 变更
- 文档结构从 9 章扩展到 18 章
- drawio 图从 4 个增加到 88+ cells
- OpenAPI tags 从 10 增加到 16

---

## [3.0.0] - 2026-08-12

### 新增
- **技术方案书 v3.0 终稿**：12 万字，涵盖：
  - 12 章核心内容（项目背景/架构/技术栈/视频模块/文档模块/异步流水线/数据库/安全策略/部署运维/优势风险/实施计划/总结）
  - 6 类核心模块（鉴权/播放/上传/审批/水印/管理）
  - 7 层纵深防御体系
  - 4 级密级模型（L1-L4）
  - 风险登记册（15 项）
  - 成本估算（首年 ¥326 万）
- **4 张关键示意图**：
  - 图 1：整体架构图（四层 + 三横向）
  - 图 2：视频播放时序图（14 个时序步骤）
  - 图 3：数据库 ER 图（11 张表）
  - 图 4：数据流图（DFD Level 0）
- **Docker Compose 开发环境**：
  - 9 个服务（MySQL 8.0 / Redis 7.2 / MinIO / RabbitMQ / Vault / ES 8 / Prometheus / Grafana / Prism Mock）
  - 健康检查 + 数据卷持久化 + 网络隔离
  - 一键启动所有依赖
- **Flyway 数据库迁移**：
  - V20260812_1000__init_schema.sql（26 张表 + 完整 FK 约束）
  - V20260812_2000__seed_baseline.sql（4 角色 17 权限 + 管理员种子）
- **Kubernetes Helm Chart**：
  - Chart.yaml + values.yaml + 9 个 templates
  - Deployment/Service/Ingress/ConfigMap/Secret/PDB/HPA/ServiceMonitor
  - Pod Security Context（runAsNonRoot + readOnlyRootFilesystem）
- **CI/CD 流水线**：
  - GitHub Actions：7 阶段（lint → db-migration → backend → worker → frontend → docker → security）
  - GitLab CI：9 阶段（含 staging/prod 部署）
  - 镜像构建 + Helm Chart 打包 + Release
- **API Mock**：
  - Prism 动态 Mock Server（dev/mock/prism.sh）
  - Postman Collection 集合（4 大类 9 端点）
- **7 篇架构决策记录（ADR）**：
  - 0001 DLP 自研 vs 采购
  - 0002 Spring Boot 3.x 选型
  - 0003 JWT 鉴权方案
  - 0004 视频加密方案
  - 0005 终端透明加密（开源自研）
  - 0006 国密 + 国际双轨
  - 0007 Helm K8s 部署
- **OpenAPI 3.0 规范**（v3.0）：30 端点 / 26 Schema / 10 Tags
- **数据库 Schema 文档**（v3.0）：17 张表详细定义
- **可观测性**：
  - Prometheus 配置（7 个 scrape job）
  - Grafana 仪表板 JSON
  - Alertmanager 路由配置
- **运维 SOP**：
  - 01 故障应急响应
  - 02 灾备演练（RTO 30min / RPO 5min）
  - 03 性能调优
  - 04 变更管理

### 修复
- v1.0 / v2.0 评审识别的 16 项问题全部修复：
  - P0 5 项（MD5 矛盾/移动端/驱动失效/合规拒签/工期）
  - P1 7 项（批量/搜索/标签/通知/备份/鉴权/时区）
  - P2 4 项（术语/视觉资产/缓存/文档化）

### 变更
- 从 v2.0 评审修订版（30 pages）扩展到 v3.0 终稿（762 KB）
- 从"评审修订版"升级为"完整方案"

---

## [2.0.0] - 2026-08-12

### 新增
- 评审修订版技术方案书
  - P0 修复 5 项：HLS≠DRM 定位/移动端策略/驱动失效兜底/合规拒签/工期
  - P1 修复 7 项：批量操作/搜索/标签/通知/备份/鉴权/时区
  - P2 修复 4 项：术语统一/视觉资产/缓存策略/文档化
- 19 张表格 + 7 张关键示意图
- 风险登记册 15 项
- 成本估算（首年 ¥326 万）

---

## [1.0.0] - 2026-08-12

### 新增
- 初版技术方案书
- 4 大核心模块：
  - 动态 Token 鉴权 + Secure Link
  - HLS 视频切片 + AES 加密
  - 终端透明加密（开源自研）
  - 频域盲水印 / 视频帧指纹
- 2 阶段审核（部门 + 法务 + 阅后即焚）
- 30+ 章节，12 万字

### 局限（已通过 v2.0/v3.0 修复）
- HLS+AES-128 被误定位为 DRM
- 驱动层工作量被严重低估（仅一句话带过）
- 合规风险（个保法/等保/国密）完全未涉及
- 工期低估（11-16 周，实际 35-49 周）
- 缺乏威胁建模、ADR、SOP、CI/CD、Helm Chart
- 视觉资产（架构图/时序图/ER图）缺失

---

## 版本对比

| 版本 | 发布日期 | 状态 | 主要改进 |
| --- | --- | --- | --- |
| [3.1.0] | 2026-08-12 | ✅ 当前 | API + DB + drawio 全面扩展 |
| [3.0.0] | 2026-08-12 | ✅ 历史 | 完整方案书 + 全部 DevOps 基础设施 |
| [2.0.0] | 2026-08-12 | ✅ 历史 | 评审修订版（16 项问题修复）|
| [1.0.0] | 2026-08-12 | ✅ 历史 | 初版方案书 |

## 提交类型说明

- **新增** (Added)：新功能
- **变更** (Changed)：已有功能的变更
- **废弃** (Deprecated)：即将移除的功能
- **移除** (Removed)：已移除的功能
- **修复** (Fixed)：Bug 修复
- **安全** (Security)：安全相关变更

## 版本号规则

- **MAJOR**（主版本）：不兼容的 API 变更
- **MINOR**（次版本）：向后兼容的功能新增
- **PATCH**（修订号）：向后兼容的 Bug 修复

预发布版本：`-alpha.1` / `-beta.1` / `-rc.1`

## 链接

- [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)
- [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)
- [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)
- [项目主页](README.md)
- [架构文档](ARCHITECTURE.md)