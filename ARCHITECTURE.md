# EDAM 架构文档

> 本文档从全局视角描述 EDAM 系统的架构设计、关键决策、组件交互、部署拓扑与演进路径。
> 详细 ADR 见 [`docs/adr/`](docs/adr/)；API 规范见 [`doc/openapi.yaml`](doc/openapi.yaml)；数据模型见 [`doc/database_schema.md`](doc/database_schema.md)。

## 1. 架构原则

| 原则 | 说明 |
| --- | --- |
| **自主可控** | 全栈开源（GPL-3.0），无商业授权依赖；密码学算法可审计 |
| **零信任** | 默认不信任任何请求；最小权限；持续验证；不依赖网络位置 |
| **纵深防御** | 7 层防护（传输/鉴权/存储/视觉/频域/驱动/管理），任何一层被绕过仍有后备防线 |
| **异步解耦** | 重计算任务（HLS 切片/水印）异步化；不阻塞核心业务路径 |
| **可观测** | 指标+日志+链路追踪三位一体；告警分级；SLO 量化 |
| **GitOps** | 配置即代码；声明式；可审计；可回滚 |
| **国产化合规** | 支持国密算法套件（SM2/SM3/SM4）；满足等保三级 + 个保法 + 数据出境 |

## 2. 全局架构

### 2.1 分层架构（四层 + 三横向）

```
┌──────────────────────────────────────────────────────────────────┐
│                        前端展示层（Presentation）                │
│  Vue3 + Element Plus + DPlayer/Video.js/hls.js + Canvas 动态明水印 │
│  - SPA + 响应式 + 录屏检测 + DevTools 拦截（防误操作）          │
└──────────────────────────────┬───────────────────────────────────┘
                               │ HTTPS / WSS
┌──────────────────────────────┴───────────────────────────────────┐
│                       业务后端层（Application）                  │
│  Spring Boot 3.x + Spring Security + MyBatis-Plus + JJWT         │
│  - REST API（51 端点） + WebSocket（实时通知）                    │
│  - 鉴权 / 权限 / 业务编排 / 事务管理                              │
│  - 集成点：API 网关（限流/熔断）+ Vault（密钥）                  │
└──────────────────────────────┬───────────────────────────────────┘
                               │ AMQP (RabbitMQ)
┌──────────────────────────────┴───────────────────────────────────┐
│                    异步处理流水线（Async Pipeline）               │
│  RabbitMQ 3 队列 + Python Worker（FFmpeg + OpenCV + blind-watermark） │
│  - 视频 HLS 切片 + AES 加密 + 帧指纹提取                       │
│  - 文档频域盲水印 + 隐写术 + 格式转换                           │
│  - GPU 加速（NVIDIA T4/A10）                                     │
└──────────────────────────────┬───────────────────────────────────┘
                               │ S3 API / HLS / HTTPS
┌──────────────────────────────┴───────────────────────────────────┐
│                  流媒体与文档服务层（Streaming/Doc）             │
│  Nginx (secure_link) + FFmpeg + 文档外发网关 + MinIO             │
│  - 视频切片分发 + HLS 加密 + 防盗链                             │
│  - 文档加密 + 外发审批 + 阅后即焚                                │
└──────────────────────────────────────────────────────────────────┘
     ↓                ↓                ↓                ↓
  MySQL 8.0      Redis Cluster    MinIO 集群      Elasticsearch
  (业务数据)      (缓存/会话)      (对象存储)        (全文搜索)

      横向支撑能力
  ┌─────────────┬─────────────────┬───────────────────┐
  │  API 网关     │  可观测性层       │  密钥管理层         │
  │ Spring Cloud  │ Prometheus       │ HashiCorp Vault     │
  │ Gateway /     │ + Grafana        │ 双密钥灰度轮转      │
  │ APISIX        │ + ELK/Loki       │ Raft 共识 ≥3 节点   │
  │ (限流/熔断)    │ + SkyWalking     │ HSM 长期演进         │
  └─────────────┴─────────────────┴───────────────────┘
```

### 2.2 部署拓扑（生产环境）

```
可用区 A（主）          可用区 B（备）          异地机房
┌────────────────┐    ┌────────────────┐    ┌──────────────┐
│ K8s master × 1  │    │ K8s master × 1  │    │ 备份 Vault    │
│ K8s worker × 3  │    │ K8s worker × 3  │    │ MinIO 备份    │
│ MySQL 主库       │    │ MySQL 从库       │    │ binlog        │
│ Redis Cluster   │    │ Redis Cluster   │    │ 异地 K8s      │
│ Vault Raft (3)  │    │ Vault Raft (3)  │    │ (灾备冷启动)  │
│ MinIO 节点 1-2  │    │ MinIO 节点 3-4  │    └──────────────┘
└────────────────┘    └────────────────┘
```

**多活策略**：
- **应用层**：双活（K8s 多节点跨可用区调度）
- **数据库**：主从同步 + 读写分离
- **缓存**：Redis Cluster 跨可用区
- **对象存储**：MinIO 纠删码（4 节点起步，可容忍 1 节点故障）
- **密钥管理**：Vault Raft 共识（≥3 节点，跨可用区）

## 3. 核心数据流

### 3.1 视频播放时序（含帧指纹溯源）

```
用户浏览器          Spring Boot           Nginx          Python Worker       溯源平台
   │                   │                    │                 │                │
   │ 1. 播放请求         │                    │                 │                │
   ├──────────────────►│                    │                 │                │
   │                   │ 2. 校验权限+JWT      │                 │                │
   │                   │ 3. 查询 Redis 缓存  │                 │                │
   │                   ├────────────────────┤                 │                │
   │                   │                    │ 4. 缓存未命中    │                │
   │                   ├───────────────────►│ 5. MQ 帧指纹任务 │                │
   │                   │                    │   pHash 嵌入     │                │
   │                   │ 6. 返回 m3u8+token │                 │                │
   │ 7. GET .ts分片   │                    │                 │                │
   ├───────────────────┼───────────────────►│ secure_link 验证 │                │
   │                   │                    │ 8. 返回解密分片  │                │
   │ 9. Canvas 动态水印 │                    │                 │                │
   │ 10. 上报播放日志  │                    │                 │                │
   │                   │                    │                 │                │
   │ ──────────── 溯源流程（异步）───────────                 │                │
   │                   │                    │ 11. 发现泄露视频  │                │
   │                   │                    ├────────────────►│                │
   │                   │                    │ 12. 提取指纹     │                │
   │                   │                    │ 13. 比对员工库   │                │
   │                   │                    │ 14. 定位泄密者   │                │
```

### 3.2 文档外发审批流

```
申请人              Spring Boot        审批人          外部接收人
   │                   │                │                │
   │ 1. 发起外发请求    │                │                │
   ├──────────────────►│                │                │
   │                   │ 2. 通知待审批    │                │
   │                   ├───────────────►│                │
   │                   │                │ 3. 多级审批      │
   │                   │                │  (发起人 → 部门 │
   │                   │                │   → 法务 → 通过) │
   │                   │ 4. 审批结果     │                │
   │ 5. 通知结果       │◄───────────────┤                │
   │◄──────────────────┤                │                │
   │                   │                │                │
   │ 6. 生成外发文档（加密+水印+过期时间+最大打开次数）    │
   │                   │                │                │
   │                   │ 7. 发送链接     ├───────────────►│
   │                   │                │                │
   │                   │                │ 8. 外部打开文档  │
   │                   │                │ (审计日志记录)  │
```

## 4. 安全模型

### 4.1 七层纵深防御

| 层级 | 视频 | 文档 |
| --- | --- | --- |
| **传输** | TLS 1.2+ | TLS 1.2+ |
| **鉴权** | JWT + Nginx secure_link + 密钥轮转 | RBAC + 细粒度权限 + JWT 刷新 |
| **存储** | AES-128 HLS 切片 + MinIO 服务端加密 | 驱动层 AES-256/SM4 + MinIO 加密 |
| **视觉** | Canvas 动态明水印 | Canvas 动态明水印（在线/本地）|
| **频域** | 视频帧指纹（pHash + 帧间冗余）+ DWT 水印 | 频域盲水印（DCT/DWT）+ 隐写术 |
| **驱动** | 剪贴板管控 + 录屏检测 + 心跳离线检测 | 剪贴板管控 + 打印点阵水印 + MDM |
| **管理** | 操作日志全审计 + KMS 密钥生命周期 | 操作日志 + 密钥轮转 + 外发审批 |

### 4.2 威胁模型（STRIDE）

| 威胁 | 缓解 |
| --- | --- |
| **Spoofing** 伪造 Token | JWT 签名 + 一次性 nonce + IP 绑定 + session 校验 |
| **Tampering** 修改外发文档 | 文档加密 + 远程策略下发 + 完整性校验 + 数字签名 |
| **Repudiation** 员工否认操作 | 操作日志全审计 + 数字签名 + 区块链存证（可选）|
| **Information Disclosure** 内鬼外发 | 驱动加密 + 频域水印 + 外发审批 + 监控告警 + 帧指纹溯源 |
| **Denial of Service** 恶意刷接口 | API 网关限流 + WAF + 验证码 + 登录限流（5/min）|
| **Elevation of Privilege** 越权访问 | 零信任 + 最小权限 + 定期渗透测试 |

### 4.3 密钥生命周期

```
生成（Vault）→ 分发 → 使用 → 轮转（90 天）→ 吊销（离职/违规）→ 归档
                ↓
            审计日志（key_rotation_log）
```

- **HLS AES-128 密钥**：每次会话一次性 + 响应 `Cache-Control: no-store`
- **驱动加密密钥（AES-256/SM4）**：90 天轮转 + 双密钥灰度
- **JWT 签名密钥**：HMAC-SHA256 + 90 天轮转
- **PII 加密密钥**：Vault Transit + 多版本解密

## 5. 数据模型

### 5.1 核心表（22 张 + 2 张分表）

```
用户与权限（7 张）  → sys_user / sys_role / sys_role_permission /
                       sys_user_role / sys_session / sys_permission / sys_dept
资源管理（3 张）     → video_resource / doc_resource / file_metadata
权限关联（4 张）     → video_permission / doc_permission /
                       distribution_approval / distribution_approval_decision
审计溯源（6 张）     → play_log（按月分表）/ operation_log（按月分表）/
                       watermark_cache / key_rotation_log / driver_status /
                       external_doc_view_log
v3.1 新增（8 张）    → notification / notification_preferences / tag /
                       video_tag / doc_tag / webhook / webhook_delivery / backup
```

### 5.2 数据生命周期

| 数据 | 保留 | 归档 | 删除 |
| --- | --- | --- | --- |
| 操作日志 | 180 天 | 冷归档 | 硬删 |
| 播放日志 | 365 天 | 冷归档 | 硬删 |
| 密钥轮转日志 | 永久 | 仅密文 | 不删 |
| 视频/文档 | 用户控制 | 软删除 | 90 天后硬删 |
| 会话 | 7 天 | — | 过期即删 |

## 6. 可观测性

### 6.1 指标分层

| 层级 | 工具 | 指标 |
| --- | --- | --- |
| **应用层** | Spring Boot Actuator + Micrometer | QPS / 延迟 / 错误率 / JVM / DB Pool / MQ |
| **基础设施** | Prometheus Node Exporter | CPU / Memory / Disk / Network |
| **业务层** | 自定义指标 | 视频转码成功率 / 水印提取次数 / 审批通过率 |
| **日志** | ELK / Loki | 业务日志 / 访问日志 / 安全日志 |
| **链路追踪** | SkyWalking | 用户 → 后端 → Worker → MinIO 全链路 |

### 6.2 SLO

| 指标 | 目标 |
| --- | --- |
| 核心服务可用性 | 99.9% |
| 视频播放可用性 | 99.9% |
| 文档预览可用性 | 99.95% |
| API P95 延迟 | < 200ms |
| API P99 延迟 | < 500ms |
| HLS 视频首屏 | < 2s |
| 文档预览首屏 | < 1.5s |

### 6.3 告警分级

| 等级 | 渠道 | 响应 SLA |
| --- | --- | --- |
| **P0** | 电话 + 短信 + 钉钉 @所有人 | 5 分钟 |
| **P1** | 钉钉 + 短信 | 15 分钟 |
| **P2** | 钉钉群 | 1 小时 |
| **P3** | 邮件 | 4 小时 |

完整告警规则：[`monitoring/grafana/alerts/edam-rules.yaml`](monitoring/grafana/alerts/edam-rules.yaml)

## 7. 部署架构

### 7.1 多环境

| 环境 | 命名空间 | 副本数 | 数据库 | 同步方式 | 用途 |
| --- | --- | --- | --- | --- | --- |
| **dev** | edam-dev | 1 | H2/本地 MySQL | 自动（PR 合入） | 开发自测 |
| **staging** | edam-staging | 2 | 共享测试库 | 手动 | 集成测试 |
| **prod** | edam | 5+ | 主从 MySQL | 手动（tag 触发） | 生产环境 |

### 7.2 GitOps 流程

```
开发分支 → main → tag v*.*.* → 镜像构建 → GitOps 同步
  ↓        ↓        ↓           ↓            ↓
自动测试  自动测试  多架构镜像  GHCR 推送   ArgoCD 部署
                                              ↓
                                  staging → 验证 → prod
```

### 7.3 资源规划（5000 用户）

```
应用后端服务器 × 4    8 vCPU / 16 GB / 100 GB SSD   ¥ 200,000
GPU 节点 × 2 (T4)    16 vCPU / 32 GB / 200 GB SSD  ¥ 300,000
MinIO 节点 × 4       16 vCPU / 64 GB / 50 TB NVMe ¥ 400,000
MySQL + Redis × 2    16 vCPU / 64 GB / 1 TB SSD   ¥ 100,000
Vault 集群 × 3        4 vCPU / 8 GB / 50 GB         ¥ 60,000
────────────────────────────────────────────────────
硬件总计：约 ¥ 1,210,000 一次性 + ¥ 100,000/年
```

## 8. 性能与扩展性

### 8.1 性能基线

| 场景 | 目标 |
| --- | --- |
| 视频列表查询 | 5000 QPS |
| 视频播放鉴权 | 5000 QPS |
| 文档预览 | 10000 用户并发 |
| 单 GPU 节点 HLS 切片 | ≥ 4 路 1080p 同时 |
| 单节点水印嵌入 | ≥ 50 文档/分钟 |

### 8.2 水平扩展

- **应用层**：K8s HPA（CPU > 70% 或 Memory > 80% 自动扩容）
- **Worker**：HPA + 任务队列分片（按资源类型）
- **数据库**：未来可分库分表（按 user_id 哈希）
- **缓存**：Redis Cluster 水平扩容
- **对象存储**：MinIO 扩容（每 4 节点一组）

### 8.3 性能调优

- **JVM**：G1GC + MaxGCPauseMillis=200 + UseStringDeduplication
- **Nginx**：1.21+ + secure_link_md5_sha（SHA-256）
- **MySQL**：InnoDB Buffer Pool 2GB + 慢查询 long_query_time=1s
- **Redis**：maxmemory 1GB + allkeys-lru

## 9. 演进路线

### 9.1 短期（3 个月内）

- ✅ v3.0 终稿 + v3.1 细化（已完成）
- 🔄 视频/文档 API Controller 补全（已完成）
- 🔄 Web 前端关键页面（进行中）
- ⏳ 真实驱动层开发（Windows minifilter 优先）

### 9.2 中期（6-12 个月）

- ⏳ macOS / Linux 驱动层
- ⏳ 移动端 MDM 集成
- ⏳ Widevine DRM 集成（高敏感场景）
- ⏳ AI 增强（水印检测 + 异常行为识别）

### 9.3 长期（12+ 个月）

- ⏳ 区块链存证（不可篡改审计日志）
- ⏳ 联邦学习（跨组织异常检测）
- ⏳ 零信任架构全面落地
- ⏳ 多模态溯源（视频+音频+图像联合）

## 10. 风险与限制

| 风险 | 当前缓解 | 长期方案 |
| --- | --- | --- |
| 驱动签名 / 兼容性 | 充分测试 + 灰度发布 | 评估商业驱动模块 |
| 视频重编码绕过频域水印 | 帧指纹为主，水印为辅 | AI 增强指纹鲁棒性 |
| 内部恶意员工 | 行为审计 + 最小权限 | 零信任 + 持续验证 |
| Vault 单点故障 | Raft 集群 ≥3 | HSM 长期演进 |
| 海外业务数据出境 | 数据本地化 | 多区域部署 + 合规评估 |

## 11. 相关文档

| 文档 | 路径 | 说明 |
| --- | --- | --- |
| 主方案书 | [`doc/企业全格式数字资产防泄密系统技术方案书.docx`](doc/) | v3.0 完整方案 |
| OpenAPI 规范 | [`doc/openapi.yaml`](doc/openapi.yaml) | 51 端点 / 36 schema |
| ER 文档 | [`doc/database_schema.md`](doc/database_schema.md) | 22 表详细定义 |
| 架构决策 | [`docs/adr/`](docs/adr/) | 7 篇关键决策 |
| 评审报告 | [`modify/`](modify/) | 3 份评审记录 |
| 运维 SOP | [`ops/sop/`](ops/sop/) | 故障 / 灾备 / 性能 / 变更 |
| Helm Chart | [`helm/edam/`](helm/edam/) | K8s 部署 |
| Dockerfile | [`docker/`](docker/) | 多阶段构建 |

---

**版本**：v1.0 · **日期**：2026-08-12 · **作者**：Claude Code

如有架构问题或改进建议，请提交 [Issue](https://github.com/example/edam/issues) 或创建 ADR。