# EDAM - 企业全格式数字资产防泄密系统

[![Version](https://img.shields.io/badge/version-3.4.1-blue.svg)](CHANGELOG.md)
[![Release Status](https://img.shields.io/badge/status-v3.4%E5%B7%B2%E5%8F%91%E5%B8%83-brightgreen.svg)](modify/2026-10-03-v3.4短期W1-W4收官总结.md)
[![API Coverage](https://img.shields.io/badge/API_Coverage-100%25-success.svg)](scripts/check_controller_coverage.py)
[![License](https://img.shields.io/badge/license-GPL--3.0-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![Python](https://img.shields.io/badge/Python-3.10-blue.svg)](https://python.org/)
[![Vue](https://img.shields.io/badge/Vue-3.5-brightgreen.svg)](https://vuejs.org/)
[![等保](https://img.shields.io/badge/%E7%AD%89%E4%BF%9D-%E4%B8%89%E7%BA%A7-blue.svg)](modify/2026-08-12-等保三级测评准备.md)

> 基于开源技术栈的零商业授权费用方案，覆盖视频与文档的全链路防泄密。
> **v3.4 已发布**（2026-11-05）：v3.3 之后的授权模型、审计闭环与播放链路稳定性工作全部收口。

## 概述

EDAM 是一套面向中大型企业的数字资产防泄密系统，围绕"视频 + 文档"两大资产，提供：

- 🛡️ **四层架构**：前端展示 / 业务后端 / 异步流水线 / 流媒体与文档服务
- 🔐 **七层纵深防御**：传输 / 鉴权 / 存储 / 驱动 / 视觉 / 频域 / 管理
- 📊 **四级密级**：L1 公开 / L2 内部 / L3 机密 / L4 绝密
- 🔍 **多模态溯源**：Canvas 明水印 + 视频帧指纹 + 文档盲水印 / 隐写
- 🪪 **统一权限中心**：RBAC 角色 + 资源级 ACL（密级 + 显式授权）双层判定
- 📝 **全量操作审计**：切面自动留痕，成功 / 失败 / 拒绝三态
- 🌐 **多环境 GitOps**：dev / staging / prod 自动化部署
- 📜 **完整合规**：等保三级 + 国密 + 个保法 + 数据出境

---

## 目录

- [快速开始](#快速开始)
- [系统架构](#系统架构)
- [权限模型](#权限模型)
- [项目结构](#项目结构)
- [核心 API](#核心-api)
- [数据模型](#数据模型)
- [开发与测试](#开发与测试)
- [部署](#部署)
- [监控](#监控)
- [安全](#安全)
- [已知限制](#已知限制)

---

## 快速开始

### 前置条件

- Docker 24+ & Docker Compose v2
- Kubernetes 1.24+（生产）
- Git 2.30+
- Java 17 / Python 3.10 / Node.js 18（开发）

### 1. 本地开发

```bash
# 1. 克隆仓库
git clone https://github.com/simonyxd123/edam.git
cd edam

# 2. 启动所有依赖（MySQL / Redis / MinIO / RabbitMQ / Vault / ES / Prometheus / Grafana / Mock）
cd dev && docker-compose up -d && cd ..

# 3. 启动后端（端口已在 application.yml 里配好，不用带参数）
cd backend && mvn spring-boot:run

# 4. 启动 Worker
cd ../worker && pip install -r requirements.txt
PYTHONPATH=src python -m uvicorn edam_worker.main:app --reload --port 8001

# 5. 启动前端
cd ../web && npm install && npm run dev
```

> **worker 的包在 `worker/src/` 下**，不设 `PYTHONPATH` 会 `ModuleNotFoundError`。

**端口对照**（全链路统一 8092）：

| 服务 | 端口 | 说明 |
|---|---|---|
| 前端 Vite dev server | 5173 | 代理 `/api` → `localhost:8092` |
| 后端 | **8092** | context-path `/api/v1` |
| MySQL | **3307** | 容器内仍是 3306，对外映射到 3307 |
| Redis | 6379 | |
| MinIO | 9000 / 9001 | 9001 = 控制台 |
| RabbitMQ | 5672 / 15672 | 15672 = 管理台 |
| Vault | 8200 | |
| Worker | 8001 | 指标端口 9100 |
| Grafana | 3000 | |

> 端口散落在 12 个文件里，改的时候必须一起改 ——
> `scripts/` 与 `dev/README.md` 里有端口一致性校验。

默认管理员：`E000001` / `admin123`（**仅开发环境**；生产由 `ADMIN_INITIAL_PASSWORD` 注入，
未配置则**不创建**任何默认账号 —— fail-closed）。

详见 [`dev/README.md`](dev/README.md)。

### 2. 部署到 Kubernetes

```bash
# 1. 安装 ArgoCD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 2. 部署 EDAM（三个环境：dev / staging / prod）
kubectl apply -f gitops/argocd/projects/edam-appproject.yaml
kubectl apply -f gitops/argocd/applications/edam-dev.yaml
kubectl apply -f gitops/argocd/applications/edam-staging.yaml
kubectl apply -f gitops/argocd/applications/edam-prod.yaml

# 3. dev 自动同步（main 合入后 3 分钟内）；staging / prod 需手动同步 + 审批
argocd app list
argocd app sync edam-dev --watch
```

### 3. 移动 SDK 集成

```bash
# iOS（Swift Package Manager）
# Xcode → File → Add Packages → mobile-sdk/ios/EDAMPlayer

# Android（Gradle）
# settings.gradle.kts:
#   include(":edam-player")
#   project(":edam-player").projectDir = file("../mobile-sdk/android/edam-player")
```

SDK 自带灰度开关（`GrayReleaseConfig`），当前版本 **v3.2.0**。

---

## 系统架构

```
┌─────────────────────────────────────────────┐
│ 前端展示层（Vue3 + Canvas 动态明水印）      │
├─────────────────────────────────────────────┤
│ 业务后端层（Spring Boot 3.3 + JWT + RBAC）   │
│   20 个 Controller / 98 个 REST 端点          │
├─────────────────────────────────────────────┤
│ 异步处理流水线（RabbitMQ + Python Worker）   │
│   3 条队列：video/document preprocess + watermark │
├─────────────────────────────────────────────┤
│ 流媒体与文档服务层（Nginx + FFmpeg + MinIO）  │
└─────────────────────────────────────────────┘
     ↓                ↓                ↓
   MySQL 8.0    Redis Cluster    MinIO / Vault
   + ES（搜索）  + Prometheus / Grafana
```

详细设计见 [`ARCHITECTURE.md`](ARCHITECTURE.md) 与 [`docs/adr/`](docs/adr/)（7 篇 ADR）。

### 组件规模

| 组件 | 技术 | 规模 |
|---|---|---|
| 后端 | Java 17 + Spring Boot 3.3.0 + MyBatis-Plus 3.5.7 | 20 Controller / 98 端点 / 27 个测试类 |
| Worker | Python 3.10 + FastAPI + aio-pika | 3 条队列 / 6 个测试文件 |
| 前端 | Vue 3.5 + Vite 5 + Pinia + Element Plus | 11 条路由 / 10 个视图 / 7 个测试文件 |
| 数据库 | MySQL 8.0 + Flyway | 15 个迁移 / **35 张表** |
| 移动 SDK | Swift + Kotlin | v3.2.0 |

---

## 权限模型

这是整个系统的核心，**分两层**：

### 第一层：RBAC —— 角色决定「能做什么操作」

`sys_permission` 目录里有 **46 条**权限码，格式固定 `{资源}:{动作}`。
四个预置角色：

| 角色 | 说明 | 权限 |
|---|---|---|
| `admin` | 系统管理员 | 全部权限（`PermissionService` 短路返回目录全集） |
| `dept_manager` | 部门经理 | 本部门资源的上传 / 编辑 / 分享 / 审批 / 删除 |
| **`employee`** | **普通员工** | **只读**（10 条白名单，全是 `action='read'`） |
| `auditor` | 审计员 | 只读 + 审计导出 |

**普通员工是只读的** —— 能看列表、详情、播放、预览、水印溯源、搜索；
不能上传、编辑、删除、分享、外发、审批、管理。

> 这条基线用**白名单**而不是"把 upload 那几条删掉"，因为权限目录会随功能增长。
> 减法只能挡住**已知**的写权限；以后有人新增一个 `xxx:export` 又在管理界面
> 勾给了 employee，普通员工就会**默默地**多出一项写权限 —— 这在防泄密系统里
> 是最不能接受的漂移。白名单的语义是「**没显式列出来的，一律没有**」。
>
> 权威来源：[`EmployeePermissions.java`](backend/src/main/java/com/example/edam/security/EmployeePermissions.java)，
> 由 `EmployeePermissionsTest` 保证它与迁移 SQL 逐字一致。

### 第二层：资源级 ACL —— 密级决定「能看哪个资源」

`video_permission` / `doc_permission` 两张表存显式授权，动作用位掩码：

| 位 | 含义 | 视频 | 文档 |
|---|---|---|---|
| 1 | view | ✅ | ✅ |
| 2 | download | ✅ | ✅ |
| 4 | edit | ✅ | ✅ |
| 8 | delete | ✅ | ✅ |
| 16 | distribute | ✅ | ✅ |
| 32 | print | — | ✅ |

**可见性判定顺序**（`VideoPermissionService` / `DocPermissionService`）：

1. admin → 允许
2. 上传者本人 → 允许
3. 有未过期的 `view` 位授权 → 允许（**显式授权优先于密级**）
4. L1 / L2 → 允许
5. L3 → 上传者与访问者**同部门**
6. L4 → 拒绝（只能靠显式授权）

> **下载权与可见性是分开判定的**。方案书 5.6.1【例外】：「L1 公开资源允许下载；
> L2-L4 默认禁止」—— 所以 L2 文档是"能预览但不能下载"，
> `video:download` / `document:download` 这两条权限码**没有**对应的角色级端点校验。

### 权限门在哪

- **后端**：`@PreAuthorize("hasAuthority('video:upload')")` —— 98 个端点里 **48 个**有角色门。
  哪些端点有门、门码是什么，见 [`doc/openapi.yaml`](doc/openapi.yaml) 的
  `x-required-permission` 扩展。
- **前端**：`v-permission` 指令按权限**隐藏**按钮（`display:none` + `data-permission-denied` 标记）。
  > 隐藏 ≠ 拦截。前端只是不给看，真正的拦截在后端 —— 绕过前端直接调接口一律 403。
  > 指令是"隐藏"而不是"从 DOM 删掉"：删元素会和 Vue 的虚拟 DOM 打架（vnode 还指着那个节点，
  > 后续 patch 抛 `removeChild` 异常）。

---

## 项目结构

```
edam/
├── doc/                          # 方案与 API 规范
│   ├── 企业全格式数字资产防泄密系统技术方案书.docx   # 主方案书（权威基准）
│   ├── openapi.yaml              # OpenAPI 3.0 规范（77 paths / 98 端点 / 18 tag / 40 schema）
│   ├── database_schema.md        # 数据表详细定义（35 张表）
│   ├── 2026-11-05-普通员工使用手册.pdf    # 员工手册
│   ├── 2026-11-05-管理员使用手册.pdf      # 管理员手册
│   ├── 图1-图4 PNG + drawio     # 架构/时序/ER/数据流
│   └── ER图.drawio / 架构图.drawio / ...  # 可编辑源文件
│
├── modify/                       # 评审报告与变更记录（50+ 篇）
│
├── backend/                      # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/edam/
│       │   ├── controller/       # 19 个 Controller（+ security/webauthn/ 1 个）
│       │   ├── service/          # 13 个业务 Service
│       │   ├── security/         # JWT + Spring Security + RBAC + 密级 + SSO
│       │   ├── audit/            # @AuditOp 注解 + AOP 切面
│       │   ├── model/ repository/ mapper/
│       │   └── exception/        # GlobalExceptionHandler（Problem Details）
│       └── resources/db/migration/   # ★ Flyway 迁移的唯一权威来源（15 个 / 35 表）
│
├── worker/                       # Python Worker（异步处理）
│   ├── requirements.txt
│   ├── pytest.ini
│   ├── tests/                    # 6 个测试文件
│   └── src/edam_worker/
│       ├── main.py               # FastAPI + RabbitMQ 消费 + 重试 + 自检
│       ├── processors/           # video / document / watermark
│       └── fingerprint/          # pHash / 盲水印 / 泄露检测
│
├── web/                          # Vue 3 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── views/                # 10 个视图
│       ├── api/                  # 9 个 API 模块（+ 2 个 spec）
│       ├── stores/               # Pinia（user / 权限判定）
│       ├── router/               # 11 条路由（meta.permission 守卫）
│       └── utils/permission.ts   # v-permission 指令
│
├── dev/                          # 本地开发
│   ├── docker-compose.yml        # 9 服务
│   ├── README.md                 # 启动流程 + 端口对照 + Flyway 排障
│   ├── cdc/                      # Debezium + ES CDC（独立进程）
│   ├── elk/ monitoring/ waf/ mock/
│
├── helm/edam/                    # K8s Helm Chart（version 1.1.0 / appVersion 3.4.1）
├── docker/                       # backend / worker / web Dockerfile
├── gitops/                       # ArgoCD：argocd/ + overlays/{dev,staging,prod}
├── e2e/                          # Playwright + Cypress
├── mobile-sdk/                   # iOS (SPM) + Android (Gradle)
├── perf/k6/                      # 压测套件（4 档位）
├── docs/adr/                     # 架构决策记录（7 篇）
├── ops/sop/                      # 运维 SOP（5 篇）
├── monitoring/                   # Grafana 看板 + Prometheus 告警
├── scripts/                      # 自动化脚本 + 规范校验
├── .github/workflows/            # CI / perf / release / security-scan
├── .gitlab-ci.yml                # GitLab CI（lint → test → build → deploy）
│
├── ARCHITECTURE.md  SECURITY.md  CHANGELOG.md  CLAUDE.md  README.md
```

---

## 核心 API

完整规范：[`doc/openapi.yaml`](doc/openapi.yaml)（**77 paths / 98 端点 / 18 tag / 40 schema**）。

| Tag | 端点数 | 说明 |
|---|---|---|
| videos | 10 | 视频 CRUD、批量删除、细粒度授权 |
| documents | 10 | 文档 CRUD、批量删除、细粒度授权、预览 |
| permissions | 9 | 权限目录、角色 CRUD、用户角色绑定 |
| auth | 7 | 登录 / 刷新 / 登出 / 当前用户 / 改密 / SSO 换 token |
| users | 7 | 用户 CRUD、密码重置、密钥吊销 |
| notifications | 6 | 通知列表、已读、偏好设置 |
| tags | 6 | 标签 CRUD、视频打标 |
| playback | 5 | 播放 token、m3u8、分片、密钥、日志上报 |
| distribution | 5 | 外发审批：提交 / 查询 / 批准 / 驳回 / 撤销 |
| health | 4 | Liveness / Readiness / Components |
| webhooks | 4 | 回调注册与投递历史 |
| audit | 5 | 日志查询、详情、导出、操作类型与资源类型枚举 |
| watermarks | 3 | 水印提取、缓存列表、缓存清理 |
| admin | 3 | 备份触发 / 恢复 |
| preview | 2 | 文档预览 / 下载 |
| search | 2 | 视频 / 文档检索 |
| webauthn | 6 | FIDO2 注册与登录、凭据管理 |
| dashboard | 1 | 仪表板统计 |

> 端点数与实现的对账由 `scripts/check_controller_coverage.py` 自动跑，
> 当前 **98 ↔ 98，100% 覆盖**。

---

## 数据模型

完整 Schema：[`doc/database_schema.md`](doc/database_schema.md)。

```
用户与权限  → sys_user / sys_dept / sys_role / sys_permission / sys_role_permission
              sys_user_role / sys_session / sys_password_history / webauthn_credential
资源管理    → video_resource / doc_resource / file_metadata
权限关联    → video_permission / doc_permission / distribution_approval(+_decision)
审计溯源    → play_log / operation_log / watermark_cache / key_rotation_log
              video_fingerprint / doc_watermark / leak_detection
数据分级    → data_classification_audit / data_classification_rule
标签通知    → tag / video_tag / doc_tag / notification / notification_preferences
              webhook / webhook_delivery
终端与运维  → driver_status / backup / external_doc_view_log
```

**迁移规范**：

- 唯一权威来源 `backend/src/main/resources/db/migration/`（`dev/db/migration/` 已废弃）
- `validate-on-migrate: true` —— **改已应用的迁移会让所有环境启动失败**，只能加新迁移
- 索引：高频查询在 `file_hash` / `upload_time` / `user_id` / `access_time` 上建联合索引

---

## 开发与测试

### 编译

```bash
cd backend && mvn -B compile     # 后端
cd worker  && pytest              # Worker
cd web     && npm run build       # 前端（build 内置 vue-tsc 类型检查）
```

### 测试

| 项目 | 命令 | 规模 |
|---|---|---|
| 后端 | `cd backend && mvn test` | 26 个测试类 / **183 用例** |
| 前端 | `cd web && npx vitest run` | 7 个文件 / **58 用例** |
| Worker | `cd worker && pytest` | 6 个文件 / **68 用例**（2 个需 ffmpeg / PyPDF2，跳过） |
| E2E | `cd e2e && npx playwright test` | Playwright + Cypress |

> 后端测试需要**真实的 MySQL**（迁移是 MySQL 方言，不用 H2）。
> 本机没起 MySQL 时，`ApplicationStartupTest` 的 6 个用例会因
> "Communications link failure" 报错 —— 这是**环境问题，不是代码问题**，
> CI 里 MySQL / RabbitMQ 是 service 容器。

### 代码质量门禁

```bash
# API 规范校验（YAML 语法 + 必需字段 + $ref 可解析）
./scripts/validate-openapi.sh

# Controller ↔ OpenAPI 对账（应输出 98 ↔ 98 / 100%）
python scripts/check_controller_coverage.py
```

---

## 部署

### 环境

| 环境 | 命名空间 | 副本数 | 同步方式 |
|---|---|---|---|
| dev | edam-dev | 1 | 自动（main 合入后 3 分钟） |
| staging | edam-staging | 2 | 手动 |
| prod | edam | 5+（HPA 5-20） | 手动（tag 触发 + 审批） |

> ⚠️ **Helm overlay 覆盖 base**：改 `helm/edam/values.yaml` 前**必须**查
> `gitops/overlays/*/values.yaml` —— overlay 会静默覆盖 base 的同名项，
> 生产环境可能悄悄用着旧值。
> CI 部署时用 `--set *.image.tag=$IMAGE_TAG`（commit SHA）覆盖镜像 tag，
> 必须三个服务（backend / worker / web）一起覆盖，否则会出现
> 「backend 滚到新版本、worker 还停在旧 tag」。

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

---

## 监控

[Grafana Dashboard](http://localhost:3000) 包含：

- 业务总览（QPS / 错误率 / P99 / 在线用户）
- 后端 JVM / 数据库 / MQ 详情
- Nginx 流量 / Secure Link 命中率
- 业务指标（播放 / 上传 / 审批 / 转码队列深度）

告警规则覆盖：后端（错误率 / 延迟 / Pod 重启 / 内存）、数据库（慢查询 / 主从延迟）、
Redis / RabbitMQ / Vault、业务级（视频转码失败 / 队列卡死 / 可疑登录 / 外发撤销 / 密钥轮转）。

Worker 自带自检端点（`/health/live` / `/health/ready` / `/health/worker`）与
运维接口（`/ops/videos/{id}/reprocess-hls`），可单独触发失败任务的补跑。

---

## 安全

### 加密策略

- **传输**：TLS 1.2+
- **应用层**：AES-256 / SM4（驱动）；AES-128（HLS 切片）
- **密钥管理**：HashiCorp Vault 双密钥灰度轮转
- **Token**：HMAC-SHA256 签名，**不携带 PII**
- **Worker 回调**：`X-Worker-Token` 头 + 常量时间比对，**未配置即一律拒绝**（fail-closed）

### 审计

所有写操作经 `@AuditOp` 注解 + AOP 切面自动留痕，落 `operation_log` 表：

| 字段 | 说明 |
|---|---|
| `result` | 三态：`1` 成功 / `2` 失败 / `3` 拒绝（越权访问也要留痕） |
| `detail` | 脱敏后的参数摘要（密码 / token / secret 等键名自动打码） |
| `http_method` / `endpoint` / `status_code` / `duration_ms` | 请求元信息 |
| `user_id` | **可空** —— 未登录的登录失败、越权尝试也要能记下来 |

> 敏感信息约定：**日志中不得打印 token 本身**（连哈希都不打）。

### 漏洞报告

见 [`SECURITY.md`](SECURITY.md)。

---

## 已知限制

诚实起见 —— 下面这些是**代码里还没接线**的部分，不要按文档标题误以为已经可用：

| 能力 | 现状 |
|---|---|
| 盲水印提取 | **已接线**（v3.4.1）：`POST /watermarks/extract` 把文件转发给 Worker 的 `/internal/watermark/extract` 真提取，按水印文本反查文档与上传者。提取失败（Worker 不可达 / 算法缺失）返回 **503**，绝不伪装成"没查到泄露"。当前支持 `image` / `pdf`；`video` / `docx` / `xlsx` 明确报 400 |
| 密级自动识别 | **服务端裁决已接线**（v3.4.1）：最终密级 = max(服务端基线, 客户端声明)，客户端只能往严里选。但基线只是**启发式**（文件名关键词 / 文件大小 / 上传者部门）—— 文件名和大小都是客户端可控的，真正的内容级识别需要文档分析引擎，**尚未实现** |
| 国密链路 | `AlgorithmRouter`（标记 `@Deprecated(since="3.4.1")`）与 `SmJwtTokenProvider` **均未被引用** —— 现役 JWT 仍是 HMAC-SHA256 |
| 通知 / Webhook | **注册与读侧已真接线**（v3.4.1）：回调注册真落库（secret 明文只返回一次，库里存哈希）、通知列表 / 标记已读 / 通知偏好都真读写数据库。但**投递链路仍未接线** —— 没有任何事件会触发回调（签名需要 secret 明文而表里只有哈希，且首次投递没有触发入口）；通知也**没有生产者**，所以列表暂时是空的 |
| `q.watermark` 队列 | **已删除**（v3.4.1）—— 它从来没有生产者，真实的水印嵌入走 `q.document.preprocess`。留着一条"已声明、已绑定、已被监控、但永远是空"的队列，会让运维误以为这条链路是活的 |
| 泄露检测（帧指纹比对） | 算法**已统一且可用**（`POST /ops/leak-detect`，受 `OPS_TOKEN` 保护）。但**三段链路尚未接线**：① 按观看用户入库指纹（`video_fingerprint` 表无写入方，现在比对的是上传时生成的 `fingerprint.json`，归属粒度是"视频"而非"用户"）② 自动触发比对（何时算"疑似泄露"是产品决策）③ 告警回后端（`leak_detection` 表与告警服务均无写入方） |
| 文档处理回调 | **已闭环**（v3.4.1）：Worker 处理完文档会回调 `PATCH /documents/{id}/status`，水印与预览两个状态分开报，`preview_path` / `watermark_path` 一并回写 |
| ES CDC | 在 `dev/cdc/` 是独立 Python 进程，**后端没有 ES 客户端** —— 搜索走的是标题模糊匹配，不是 Elasticsearch |
| `fingerprint/` 下三个 Service | **算法已统一**（v3.4.1）：pHash 与盲水印各只有一份实现，两边逐位相同（此前 imagehash 与手写 DCT 并存 ⇒ 指纹对不上、匹配率 ≈ 0 且不报错）。泄露比对已可通过 `POST /ops/leak-detect` 调用，但产品链路未闭环（见上一行） |
| SSO | 默认 `edam.sso.enabled: false` |

方案书里的**驱动层**（Windows minifilter / macOS Endpoint Security / Linux FUSE）
在本仓库中**没有实现** —— 只有协议与设计文档。

---

## 贡献

1. Fork 仓库
2. 创建特性分支（`git checkout -b feature/amazing`）
3. 提交变更（`git commit -m 'feat: 添加 amazing 功能'`）
4. 推送分支（`git push origin feature/amazing`）
5. 提交 PR（参考 [`PR 模板`](.github/pull_request_template.md)）

---

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
- **问题反馈**：https://github.com/simonyxd123/edam/issues
- **邮件**：team@example.com

---

**⚠️ 重要提示**：

1. 生产环境部署前，**必须**修改默认密钥（admin / admin123）
2. 所有密钥应通过 Vault 管理，**禁止**明文存储在 Git
3. 定期更新依赖、订阅安全公告
4. 参考 [`ops/sop/`](ops/sop/) 制定运维流程
