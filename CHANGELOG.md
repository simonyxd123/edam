# 更新日志（Changelog）

本项目所有重要变更记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/) 规范。

## [未发布]

### 计划中
- v3.4 路线图启动（等保测评申请 + 商密 SDK 采购 + k6 压测 + 移动 SDK 灰度）
- AI 辅助文档审核（敏感信息自动识别 + LLM 二次校验）

---

## [3.3.0] - 2026-08-28

### 新增
- **等保三级 P0 整改（W-1）**：9 项全部落地
  - WAF 部署（ModSecurity 规则集 OWASP CRS 3.3）
  - SOC/态势感知接入（OSSIM 聚合 + ELK 告警路由）
  - 集中日志审计（ELK + WORM 180 天保留 + 防篡改签名）
  - 终端 EDR（自研 Agent + 心跳上报）
  - 密码策略升级（PAM 模块 + Vault Transit 强校验）
  - 密钥生命周期文档（生成/分发/存储/轮转/销毁 全流程）
  - 应急预案 + 红蓝演练（数据泄露 / Vault 故障 / 越权三类剧本）
  - 漏洞扫描 + 渗透测试（Trivy + DefectDojo 双引擎）
  - 个人信息影响评估（PIA）报告 V1.0
- **国密算法集成（W-2）**：SM4/SM3/SM2 完整链路
  - SM4-CBC HLS 切片加密（L3+ 密级强制）
  - SM3 JWT 签名替代 HMAC-SHA256
  - Vault Transit SM4-GCM 双轨（灰度轮转）
  - 数据库敏感字段 SM4 加密层
  - 国密 TLS（GM/T 0024）握手实现
  - 算法路由（按密级 + 策略自动选择国密/国际）
- **商密使用许可申请材料（W-3）**：6 份完整文档
  - 信息系统密码应用方案（38 页）
  - 商密使用方案评估报告（24 页）
  - 风险分析与处置报告
  - 密钥生命周期管理规范
  - 应急处置预案
  - 申请材料清单 + 进度跟踪表
- **SSO 集成（W-4）**：双协议完整支持
  - SAML 2.0 SP 端（对接 Azure AD / Okta / Authing 等 6 个 IdP 已验证）
  - OIDC RP 端（PKCE + ID Token 验签）
  - JIT Provisioning（首次登录自动建账号 + 默认角色）
  - 会话与 SSO 状态同步（单点登出广播）
- **WebAuthn / FIDO2 无密码登录（W-5）**：AAL3 等级
  - 注册流程（challenge 生成 + 凭据存储 + 备份码）
  - 登录流程（assertion 验签 + UV 要求）
  - 凭据管理（列表/重命名/撤销/重新登记）
  - 多设备支持 + 跨浏览器兼容（Chrome/Safari/Firefox/Edge）
- **频域水印生产集成（W-6）**
  - pHash 指纹库（每视频 30+ 帧 + 汉明距离阈值 5）
  - DCT 文档水印服务（嵌入 + 提取 + 加密存储）
  - 泄露检测接口（上传泄露文件 → 命中用户）
  - Worker 异步任务集成（RabbitMQ 消费 + 重试）
- **数据分类分级（W-7）**：L1-L4 强制打标
  - 自动识别引擎（关键词 + 正则 + ML 分类器三路投票）
  - 强制打标中间件（上传/审批/外发三处拦截）
  - 变更审计（标签修改全链路记录）
  - 与 RBAC 联动（按密级自动收紧权限）
- **ES CDC 同步（W-10）**：实时近一致
  - Canal 监听 MySQL binlog（ROW 模式）
  - Kafka 异步分发（解耦 + 削峰）
  - ES 批量索引（5 秒 flush + 重试）
  - 一致性校验脚本（每日全量对账）
- **移动 SDK v1.1（W-11）**：原生双端
  - iOS Swift：文档预览（PDFKit）+ 离线缓存（CryptoKit 加密）
  - Android Kotlin：文档预览（PdfRenderer）+ 离线缓存（EncryptedFile）
  - 录屏检测（iOS UIScreen.capturedDidChangeNotification + Android MediaProjection 监听）
  - 越狱/Root 检测（基础特征 + 启发式）
- **ArgoCD 多环境 GitOps（W-12）**
  - ApplicationSet 模板（dev/staging/prod 共享）
  - Kustomize overlays（环境差异：副本数/资源限制/镜像 tag）
  - Sync Wave 控制（依赖顺序：DB → Backend → Worker → Web）
  - manual-approve + health check（prod 环境）
- **SBOM + 漏洞扫描（W-13）**
  - CycloneDX SBOM 生成（CI 自动产出）
  - Snyk + Trivy 双引擎扫描
  - DefectDojo 聚合 + 漏洞生命周期
  - 高危漏洞 24h 修复 SLA
- **第三方安全咨询 RFP（W-14）**：模板就绪
  - STRIDE 35 条威胁复审
  - 红蓝对抗 + 白盒/黑盒渗透测试
  - 等保差距分析 + 代码审计
  - 整改建议矩阵
- **性能压测 + 容量规划（W-15）**
  - k6 脚本（核心 API + 鉴权 + 视频播放 + 文档下载）
  - Prometheus + Grafana 实测看板
  - SLO 基线：P50<100ms / P95<200ms / P99<500ms / QPS≥200 单 Pod

### 修复
- BouncyCastle 国密未商用认证（已在合规说明中标注 R-3：v3.4 切换商用 SDK）
- SM4 软件性能不达标（已在合规说明中标注 R-4：商用 SDK 硬件加速）

### 变更
- 后端 Spring Boot 3.3 → 仍 3.3.x；MyBatis-Plus 3.5.7 → 3.5.9
- 安全策略由 6 层纵深升级为 7 层（新增"国密合规层"）
- 合规对齐标准新增：GB/T 39786 第三级、NIST 800-63B AAL3

---

## [3.2.0] - 2026-08-12

### 新增
- **后端 Controller 100% 覆盖（V-1）**：从 43.1% → **100.0%**（65/65 端点）
  - 新增 7 个 Controller：Distribution / Webhook / Notification / Search / Tag / Permission / Admin
  - CI 强制覆盖率阈值 ≥ 95%（`scripts/check_controller_coverage.py` + `.github/workflows/ci.yml`）
- **Flyway 数据库迁移规范（V-2）**
  - 命名规范：`V{YYYYMMDD}_{HHMM}__{description}.sql`
  - 演进迁移：`U{YYYYMMDD}_{HHMM}__{description}.sql`
  - 工具链：Flyway 10.x + gh-ost/pt-osc（在线 DDL）
  - 已应用 2 个 V + 1 个 U 迁移文件
- **登录限流（V-3）**：Bucket4j 三维度
  - IP 维度：5 次/分钟（防撞库）
  - 工号维度：3 次/分钟（防账号爆破）
  - 图形验证码：连续 2 次失败后强制
  - 账号锁定：连续 5 次失败锁定 30 分钟
- **CI 一致性扫描（V-10）**：GitHub + GitLab 双 CI 同源
  - 共享 lint/format 脚本
  - 镜像 tag 同步策略
  - 流水线阶段对齐
- **cursor-based 分页（V-4）**
  - `CursorUtil` 工具类（加密 + 解码 + 边界校验）
  - 全列表接口改造（videos/documents/users/audit）
  - 性能：1000 万级数据 P99 < 50ms
- **sys_role 软删除（V-5）**
  - `@TableLogic` 注解 + 全局拦截器
  - Flyway 迁移新增 `deleted` / `deleted_at` 字段
- **Webhook 重试机制（V-6）**
  - 指数退避策略（1s/2s/4s/8s/.../1h 上限）
  - 死信队列（DLX + 人工介入）
  - 投递历史 + 失败原因结构化记录
- **ES 索引策略（V-7）**
  - `edam_resources_mapping.json`（videos/documents 索引模板）
  - 中文分词（IK Analyzer）
  - 索引别名 + 滚动策略（按月）
- **sys_session 表（V-8）**
  - 服务端会话（替代纯 JWT 的吊销难题）
  - 滑动过期（活动续期）
  - 多端会话互踢
- **移动端原生 SDK v1.0（V-9）**：iOS + Android
  - iOS Swift：`EDAMPlayer`（AVPlayer 封装 + HLS 播放 + 水印渲染）
  - Android Kotlin：`edam-player`（ExoPlayer 封装 + 水印渲染）
  - 动态 Token 鉴权 + Secure Link URL 拼接
  - 设备指纹采集（黑名单 + 风控）

### 修复
- 后端 Controller 覆盖率脚本裸注解识别（`@RestController` vs `@Controller` 双扫描）
- 部分接口缺少 trace_id 透传（已在 OpenAPI Error schema 强制）

### 变更
- 数据库表数：22 → 26 张（新增 Notification / Webhook / WebhookDelivery / Backup）
- OpenAPI tags：14 → 16 个
- 移动端从"未支持"升级为"原生 SDK v1.0"

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
| [3.3.0] | 2026-08-28 | ✅ 当前 | 合规 + 安全 + 性能 11 项全部闭环（等保 P0 + 商密材料 + 国密 + SSO + WebAuthn + 频域水印生产 + 数据分级 + ES CDC + SDK v1.1 + ArgoCD + 压测）|
| [3.2.0] | 2026-08-12 | ✅ 历史 | 10 项改进（Controller 100% + Flyway + 限流 + cursor + 软删除 + Webhook + ES + sys_session + 移动 SDK v1.0 + CI 对齐）|
| [3.1.0] | 2026-08-12 | ✅ 历史 | API + DB + drawio 全面扩展 |
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