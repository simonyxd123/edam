# EDAM - 企业全格式数字资产防泄密系统

[![Version](https://img.shields.io/badge/version-3.1.0-blue.svg)](CHANGELOG.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![Python](https://img.shields.io/badge/Python-3.10-blue.svg)](https://python.org/)
[![Vue](https://img.shields.io/badge/Vue-3.5-brightgreen.svg)](https://vuejs.org/)

> 基于开源技术栈的零商业授权费用方案，覆盖视频与文档的全链路防泄密。

## 概述

EDAM 是一套面向中大型企业的数字资产防泄密系统，围绕"视频 + 文档"两大资产，提供：

- 🛡️ **四层架构**：前端展示 / 业务后端 / 异步流水线 / 流媒体与文档服务
- 🔐 **七层纵深防御**：传输 / 鉴权 / 存储 / 视觉 / 频域 / 驱动 / 管理
- 📊 **三级密级**：L1 公开 / L2 内部 / L3 机密 / L4 绝密
- 🔍 **多模态溯源**：Canvas 明水印 + 频域盲水印 + 视频帧指纹 + Office 隐写
- 🪪 **统一权限中心**：RBAC + 资源级 ACL + 细粒度授权
- 🌐 **多环境 GitOps**：dev / staging / prod 自动化部署
- 📜 **完整合规**：等保三级 + 国密 + 个保法 + 数据出境

## 快速开始

### 前置条件

- Docker 24+ & Docker Compose v2
- Kubernetes 1.24+（生产）
- Git 2.30+
- Java 17 / Python 3.10 / Node.js 18（开发）

### 1. 本地开发（5 分钟启动）

```bash
# 1. 克隆仓库
git clone https://github.com/example/edam.git
cd edam

# 2. 启动所有依赖
cd dev && docker-compose up -d

# 3. 启动后端
cd ../backend && mvn spring-boot:run

# 4. 启动 Worker
cd ../worker && pip install -r requirements.txt
python -m uvicorn edam_worker.main:app --reload --port 8001

# 5. 启动前端
cd ../web && npm install && npm run dev

# 6. 访问
#   前端：   http://localhost:3000
#   后端：   http://localhost:8080/api/v1
#   MinIO：  http://localhost:9001
#   RabbitMQ：http://localhost:15672
#   Grafana： http://localhost:3000
```

默认账号：`SA0001` / `admin123`（仅开发环境）

### 2. 部署到 Kubernetes

```bash
# 1. 安装 ArgoCD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 2. 部署 EDAM（GitOps）
kubectl apply -f gitops/argocd/projects/edam-appproject.yaml
kubectl apply -f gitops/argocd/applicationset.yaml

# 3. 等待自动同步（3 分钟内）
argocd app list
```

### 3. 使用 API Mock

```bash
# 启动 Prism mock
./scripts/generate-postman.sh
# 或
./dev/mock/prism.sh

# 启动后访问 http://localhost:4010
# 导入 dev/mock/postman_collection.json 到 Postman
```

## 项目结构

```
edam/
├── doc/                          # 方案与 API 规范
│   ├── 企业全格式数字资产防泄密系统技术方案书.docx   # 主方案书 (v3.0)
│   ├── openapi.yaml              # OpenAPI 3.0 规范 (51 endpoints)
│   ├── database_schema.md        # 26 张表详细定义
│   ├── 图1-图4 PNG + drawio     # 架构/时序/ER/数据流
│   └── ER图.drawio / 架构图.drawio / ...    # 可编辑源文件
│
├── docs/adr/                     # 架构决策记录 (7 篇)
│   ├── 0001-dlp-build-vs-buy.md
│   ├── 0002-backend-framework.md
│   ├── ... (0003-0007)
│   └── template.md
│
├── modify/                       # 评审报告
│   ├── 2026-08-12-方案书评审与优化建议.md
│   ├── 2026-08-12-方案书v2.0评审.md
│   └── 2026-08-12-细化文档评审.md
│
├── backend/                      # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/example/edam/
│       ├── EdamApplication.java
│       ├── controller/   # Auth/Health/Video/Document/...
│       ├── service/      # AuthService/VideoService/...
│       ├── repository/   # MyBatis-Plus Mapper
│       ├── model/        # 实体类
│       ├── security/     # JWT + Spring Security
│       └── exception/    # GlobalExceptionHandler
│
├── worker/                       # Python Worker（异步处理）
│   ├── requirements.txt
│   ├── pytest.ini
│   ├── tests/                    # 单元测试
│   └── src/edam_worker/
│       ├── main.py               # FastAPI + aio-pika
│       ├── config.py             # 配置管理
│       └── processors/           # 视频/文档/水印
│
├── web/                          # Vue 3 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── App.vue / main.ts
│       ├── views/                # Login / Dashboard / Videos / ...
│       ├── api/                  # API 客户端
│       ├── stores/               # Pinia
│       ├── router/               # Vue Router
│       └── styles/               # SCSS
│
├── dev/                          # 本地开发
│   ├── docker-compose.yml        # 9 服务一键启动
│   ├── db/migration/             # Flyway SQL (26 表)
│   ├── db/seed/                  # 种子数据
│   ├── monitoring/               # Prometheus 配置
│   └── mock/                     # Prism + Postman
│
├── helm/edam/                    # K8s Helm Chart
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/                # Deployment/Service/Ingress/...
│
├── docker/                       # Dockerfile
│   ├── backend.Dockerfile        # 多阶段 JDK 17 → JRE 17
│   ├── worker.Dockerfile         # Python + FFmpeg + LibreOffice
│   └── web.Dockerfile            # Node 18 → Nginx 1.25
│
├── e2e/                          # E2E 测试
│   ├── playwright/                # Playwright
│   └── cypress/                  # Cypress
│
├── monitoring/                   # 监控告警
│   ├── grafana/
│   │   ├── alerts/               # PrometheusRule
│   │   └── dashboards/           # Grafana 仪表板
│   └── alertmanager/             # 路由 + 抑制规则
│
├── ops/sop/                      # 运维 SOP
│   ├── 01-incident-response.md   # 故障应急
│   ├── 02-disaster-recovery.md   # 灾备演练
│   ├── 03-performance-tuning.md   # 性能调优
│   └── 04-change-management.md   # 变更管理
│
├── gitops/                       # ArgoCD 多环境
│   ├── argocd/                   # AppProject + Applications
│   └── overlays/                 # dev/staging/prod
│
├── scripts/                      # 自动化脚本
│   ├── generate-sdks.sh          # SDK 客户端生成
│   ├── generate-postman.sh       # Postman 集合生成
│   └── validate-openapi.sh       # OpenAPI 校验
│
├── .github/                      # GitHub 配置
│   ├── workflows/                # CI / CD
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/
│
├── .gitlab-ci.yml                # GitLab CI
│
├── CLAUDE.md                     # Claude Code 项目说明
├── CHANGELOG.md                  # 变更日志
└── README.md                     # 本文件
```

## 技术栈

| 类别 | 技术 | 说明 |
|---|---|---|
| 后端 | Java 17 + Spring Boot 3.3 + MyBatis-Plus | 企业级稳定框架 |
| 异步处理 | Python 3.10 + FastAPI + aio-pika | GPU 加速视频处理 |
| 前端 | Vue 3 + Vite + Pinia + Element Plus | 现代化 SPA |
| 视频 | Nginx 1.21+ + FFmpeg + HLS + AES-128 | 工业级流媒体 |
| 对象存储 | MinIO | S3 兼容 |
| 数据库 | MySQL 8.0 + Redis Cluster | 关系型 + 高频缓存 |
| 消息队列 | RabbitMQ 3.13 | 异步处理 |
| 密钥管理 | HashiCorp Vault | 双密钥灰度轮转 |
| 容器化 | Docker + Kubernetes + Helm | 云原生 |
| 可观测性 | Prometheus + Grafana + ELK + SkyWalking | SRE 体系 |
| 安全 | Spring Security + JJWT + BouncyCastle | 国密 + 国际算法双轨 |
| CI/CD | GitHub Actions / GitLab CI | 自动化交付 |
| 部署 | ArgoCD + External Secrets | GitOps |

## 核心 API

完整 API 规范：[`doc/openapi.yaml`](doc/openapi.yaml)（51 个端点 / 36 个 schema / 16 个 tag）

主要模块：

| Tag | 端点数 | 说明 |
|---|---|---|
| auth | 4 | 登录/刷新/登出/当前用户 |
| health | 4 | Liveness/Readiness/Components |
| users | 5 | 用户 CRUD + 密钥吊销 |
| videos | 5 | 视频 CRUD + 批量 |
| playback | 3 | Token / 密钥 / 日志上报 |
| documents | 4 | 文档 CRUD + 搜索 |
| distribution | 5 | 外发审批流程 |
| watermarks | 3 | 水印提取 / 缓存 |
| audit | 3 | 审计日志查询/导出 |
| notifications | 4 | 用户通知 |
| webhooks | 4 | 回调注册与投递 |
| search | 2 | 全文搜索 |
| tags | 4 | 标签管理 |
| permissions | 3 | 角色与权限 |
| admin | 3 | 备份与恢复 |

## 核心数据模型

完整 Schema：[`doc/database_schema.md`](doc/database_schema.md)（22 张表 + v3.1 新增 8 张）

```
用户与权限  → sys_user / sys_role / sys_permission / sys_session
资源管理    → video_resource / doc_resource / file_metadata
权限关联    → video_permission / doc_permission / distribution_approval
审计溯源    → play_log / operation_log / watermark_cache / key_rotation_log
终端管控    → driver_status
通知消息    → notification / webhook / notification_preferences
```

## 架构

参考 [`doc/架构图.drawio`](doc/架构图.drawio) 和 [`docs/adr/`](docs/adr/)：

```
┌─────────────────────────────────────────────┐
│ 前端展示层（Vue3 + Canvas 动态明水印）     │
├─────────────────────────────────────────────┤
│ 业务后端层（Spring Boot 3.x + JWT）          │
├─────────────────────────────────────────────┤
│ 异步处理流水线（RabbitMQ + Python Worker）  │
├─────────────────────────────────────────────┤
│ 流媒体与文档服务层（Nginx + FFmpeg + MinIO）  │
└─────────────────────────────────────────────┘
     ↓                ↓                ↓
   MySQL 8.0    Redis Cluster    Vault KMS
   + ES（搜索）  + MinIO（对象）   + Prometheus
```

## 开发指南

### 编译

```bash
# 后端
cd backend && mvn -B compile

# Worker 测试
cd worker && pytest

# 前端构建
cd web && npm run build
```

### E2E 测试

```bash
# 启动 Prism mock 后台
./dev/mock/prism.sh &

# 跑 Playwright
cd e2e && npx playwright test

# 跑 Cypress
cd e2e/cypress && npx cypress run
```

### 数据库迁移

```bash
# 启动迁移
flyway -url=jdbc:mysql://localhost:3306/edam -user=root -password=xxx \
       -locations=filesystem:dev/db/migration migrate

# 回滚
flyway -url=jdbc:mysql://localhost:3306/edam -user=root -password=xxx \
       -locations=filesystem:dev/db/migration undo
```

## 部署

### 环境

| 环境 | 命名空间 | 副本数 | 数据库 | 同步方式 |
|---|---|---|---|---|
| dev | edam-dev | 1 | H2/本地 MySQL | 自动 |
| staging | edam-staging | 2 | 共享测试库 | 手动 |
| prod | edam | 5+ | 主从 MySQL | 手动（tag 触发） |

### 部署前检查

参考 [`ops/sop/04-change-management.md`](ops/sop/04-change-management.md)：

- [ ] CI 全绿
- [ ] Helm lint 通过
- [ ] OpenAPI 规范校验通过
- [ ] 数据库迁移 dry-run
- [ ] 预发布环境验证
- [ ] 监控告警正常
- [ ] 备份已执行
- [ ] 值班人员就位

## 监控

[Grafana Dashboard](http://localhost:3000) 包含：

- 业务总览（QPS / 错误率 / P99 / 在线用户）
- 后端 JVM / 数据库 / MQ 详情
- Nginx 流量 / Secure Link 命中率
- 业务指标（播放/上传/审批）

告警规则覆盖：

- 后端（错误率 / 延迟 / Pod 重启 / 内存）
- 数据库（MySQL 慢查询 / 主从延迟 / 连接数）
- Redis / RabbitMQ / Vault
- 业务级（视频转码失败 / 可疑登录 / 外发撤销 / 密钥轮转）

## 安全

### 加密策略

- **传输**：TLS 1.2+
- **应用层**：AES-256 / SM4（驱动）；AES-128（HLS）
- **密钥管理**：HashiCorp Vault 双密钥灰度轮转
- **PII 加密**：Vault Transit + 应用层
- **Token**：HMAC-SHA256，不携带 PII

### 漏洞报告

如发现安全漏洞，请邮件至 `security@example.com`（PGP key 在 [`docs/SECURITY.md`](docs/SECURITY.md)）。

## 贡献

1. Fork 仓库
2. 创建特性分支（`git checkout -b feature/amazing`）
3. 提交变更（`git commit -m 'feat: 添加 amazing 功能'`）
4. 推送分支（`git push origin feature/amazing`）
5. 提交 PR（参考 [`PR 模板`](.github/pull_request_template.md)）

## 许可证

[GPL-3.0](LICENSE) © 2026 EDAM Team

## 致谢

本项目基于以下开源技术（按字母顺序）：

- [Bouncy Castle](https://www.bouncycastle.org/) - 国密算法
- [DPlayer](https://dplayer.diandian.com/) - 视频播放器
- [Element Plus](https://element-plus.org/) - Vue UI
- [FFmpeg](https://ffmpeg.org/) - 视频处理
- [HashiCorp Vault](https://www.vaultproject.io/) - 密钥管理
- [MinIO](https://min.io/) - 对象存储
- [MyBatis-Plus](https://baomidou.com/) - ORM
- [Nginx](https://nginx.org/) - 反向代理
- [Pinia](https://pinia.vuejs.org/) - Vue 状态管理
- [Spring Boot](https://spring.io/projects/spring-boot) - 后端框架
- [Vue.js](https://vuejs.org/) - 前端框架

## 联系方式

- **项目主页**：https://example.com/edam
- **问题反馈**：https://github.com/example/edam/issues
- **邮件**：team@example.com

---

**⚠️ 重要提示**：

1. 生产环境部署前，**必须**修改默认密钥（admin/admin123）
2. 所有密钥应通过 Vault 管理，**禁止**明文存储在 Git
3. 定期更新依赖、订阅安全公告
4. 参考 [`ops/sop/`](ops/sop/) 制定运维流程