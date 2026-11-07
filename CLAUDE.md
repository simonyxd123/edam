# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目性质

本仓库是 **企业全格式数字资产防泄密系统（EDAM）** 的完整实现 —— 后端、前端、
Worker、部署编排与测试代码**均已落地**，不是只有方案文档。

方案书（`doc/企业全格式数字资产防泄密系统技术方案书.docx`）是**设计基准**：
代码变更要能对应到方案书的章节号。**但方案书落后于代码的地方，以代码为准**
（v3.4 已有大量实现细节未回写方案书），此时应在 `modify/` 的变更记录里
写明"实现与方案书的差异"，而不是反过来改代码去迁就旧文档。

## 目录结构

```
edam/
├── doc/                          # 方案与 API 规范
│   ├── 企业全格式数字资产防泄密系统技术方案书.docx   # 设计基准（v3.0 终稿）
│   ├── openapi.yaml              # OpenAPI 3.0 规范（77 paths / 98 端点 / 18 tag）
│   ├── database_schema.md        # 数据表详细定义（35 张表）
│   ├── 2026-11-05-普通员工使用手册.pdf / 管理员使用手册.pdf
│   └── 图1-图4 PNG + drawio     # 架构/时序/ER/数据流
│
├── modify/                       # 评审报告与变更记录（50+ 篇）
│   └── <日期>-<主题>.md
│
├── backend/                      # Spring Boot 后端（Java 17 / Boot 3.3.0）
│   └── src/main/
│       ├── java/com/example/edam/
│       │   ├── controller/       # 19 个 Controller（+ security/webauthn/ 1 个）
│       │   ├── service/          # 13 个业务 Service
│       │   ├── security/         # JWT / Spring Security / RBAC / 密级 / SSO
│       │   ├── audit/            # @AuditOp + AOP 切面
│       │   └── model/ repository/ dto/ exception/
│       └── resources/db/migration/   # ★ Flyway 迁移唯一权威来源（15 个 / 35 表）
│
├── worker/                       # Python Worker（FastAPI + aio-pika）
├── web/                          # Vue 3 前端（Vite + Pinia + Element Plus）
├── helm/edam/                    # K8s Helm Chart
├── gitops/                       # ArgoCD + overlays/{dev,staging,prod}
├── e2e/ perf/k6/ mobile-sdk/     # E2E / 压测 / 移动 SDK
├── scripts/                      # 自动化脚本 + 规范校验
├── docs/adr/                     # 架构决策记录（7 篇）
├── ops/sop/                      # 运维 SOP
│
└── README.md  ARCHITECTURE.md  SECURITY.md  CHANGELOG.md
```

## 常用命令

```bash
# 后端（mvn 不在 PATH 上，用完整路径）
cd backend && JAVA_HOME=E:/data/idea/jdk-17.0.16 \
  /e/data/idea/apache-maven-3.9.11/bin/mvn -o test

# 前端
cd web && npx vitest run          # 58 用例
cd web && npm run build           # 内置 vue-tsc 类型检查

# Worker
cd worker && pytest               # 68 用例

# 规范对账（改完端点就跑）
python scripts/check_controller_coverage.py    # 应为 98 ↔ 98 / 100%
./scripts/validate-openapi.sh

# 使用手册 PDF（改完手册 Markdown 后重新导出）
python scripts/build_manual_pdf.py
```

## 系统架构摘要

四层架构，支撑视频与文档双通道：

1. **前端展示层** — Vue3 + Element Plus + DPlayer/Video.js/hls.js + Canvas 动态明水印
2. **业务后端层** — Java 17 + Spring Boot 3.3.0 + MyBatis-Plus，统一鉴权、动态 Token、密钥下发
3. **异步处理流水线** — RabbitMQ + Python Worker（FFmpeg HLS 切片/AES 加密、OpenCV + blind-watermark 频域盲水印）
4. **流媒体与文档服务层** — Nginx（secure_link）+ FFmpeg；文档安全网关负责透明加解密、外发审批

### 核心技术栈

| 类别 | 选型 |
| --- | --- |
| 后端 | Java 17 + Spring Boot 3.3.0 + MyBatis-Plus 3.5.7 |
| 流媒体 | Nginx（secure_link）+ FFmpeg |
| 异步队列 | RabbitMQ（2 条队列：video / document preprocess） |
| 盲水印 | Python 3.10 + OpenCV + blind-watermark |
| 前端 | Vue 3.5 + Vite 5 + Pinia + Element Plus + hls.js |
| 存储 | MySQL 8.0 + Redis + MinIO |
| 容器化 | Docker + Kubernetes + Helm + ArgoCD |

### 七层纵深防御

传输层（TLS/SSL）→ 鉴权层（动态 Token + RBAC + 资源级 ACL）→ 存储层（AES-128/AES-256/SM4）
→ 驱动层 → 视觉层（Canvas 动态明水印）→ 频域层（DWT 频域盲水印/隐写术）
→ 管理层（全量操作审计 + 密钥生命周期）

> ⚠️ **驱动层在本仓库没有实现** —— 只有协议与设计文档。
> 同样未接线的还有：国密链路（`AlgorithmRouter` / `SmJwtTokenProvider` 无调用方）、
> Java 侧盲水印。详见 README 的「已知限制」。
>
> **密级服务端裁决已接线**（v3.4.1）：最终密级 = max(服务端基线, 客户端声明），
> 客户端只能往严里选。改动上传链路时**不要**把 `classification_lv` 直接透传落库 ——
> 走 `ClassificationEnforcement.resolveUploadLevel`。
> 注意基线只是启发式（文件名 / 大小 / 部门），内容级识别仍未实现。

### 核心数据表

35 张表。权限相关：`sys_user` / `sys_role` / `sys_permission`（46 条权限码）
/ `sys_role_permission` / `sys_user_role` / `video_permission` / `doc_permission`。
审计相关：`play_log` / `operation_log` / `watermark_cache` / `key_rotation_log`。

## 权限模型（**最容易改坏的地方**）

**两层**，缺一不可：

1. **RBAC（角色层）** —— `sys_permission` 目录 46 条权限码，格式 `{资源}:{动作}`。
   端点用 `@PreAuthorize("hasAuthority('video:upload')")` 校验。
   哪些端点有门、门码是什么，见 `doc/openapi.yaml` 的 `x-required-permission`。
2. **资源级 ACL（资源层）** —— `video_permission` / `doc_permission` 表 + 动作位掩码。
   可见性判定：admin → 上传者 → 显式授权（**优先于密级**）→ L1/L2 → L3 同部门 → L4 拒绝。

**普通员工（`employee`）是只读的** —— 白名单 10 条，全是 `action='read'`。
权威来源：`backend/.../security/EmployeePermissions.java`，
由 `EmployeePermissionsTest` 保证与迁移 `V20261105_1000__employee_readonly_role.sql`
**逐字一致**。改一处必须改另一处，测试会红。

> **用白名单而不是"删掉 upload 那几条"**：权限目录会随功能增长。
> 减法只能挡住**已知**的写权限；以后新增一个 `xxx:export` 又在管理界面勾给了
> employee，普通员工就会**默默地**多出一项写权限 —— 防泄密系统里最不能接受的漂移。
> 白名单的语义是「没显式列出来的，一律没有」。

**下载权与可见性分开判定**：方案书 5.6.1【例外】「L1 公开资源允许下载；
L2-L4 默认禁止」—— 所以 `video:download` / `document:download` /
`document:print` **没有**角色级端点校验，由 `DocPermissionService.canDownload`
按密级判定。别给下载端点加角色门，会破坏这条例外。

## 开发约定

- **所有代码变更需对应方案书章节**：在 commit/修改记录中引用章节号（如"5.4 细粒度权限与外发管控"）。
- **权限是数据不是代码**：改角色能干什么 = 改 `sys_role_permission` 表，
  改错了不会编译报错，只能靠 SQL 抽查 + 回归测试兜住。
- **迁移只增不改**：`flyway.validate-on-migrate: true`，
  **改已应用的迁移会让所有已部署环境启动失败**。要改就加新迁移。
- **端口全链路统一 8092**（MySQL 对外 3307，容器内仍 3306）——
  改端口要同时改 12 个文件，仓库里有端口一致性校验。
- **Helm overlay 覆盖 base**：改 `helm/edam/values.yaml` 前**必查**
  `gitops/overlays/*/values.yaml`，否则生产会静默用旧值。
- **日志中不得打印 token 本身**（连哈希都不打）。
- **修改记录**：实现完成或方案调整后，在 `modify/` 下新建 `<日期>-<主题>.md`。
- **压缩输出**：运行命令时默认使用静默或精简输出，不要输出完整日志。

## 文档同步约定

代码改了，文档要一起改 —— 权限模型尤其如此（文档漂移过一次：
普通员工手册写着"可以上传"，而代码早就改成只读了）。

受影响的文档与同步方式：

| 文档 | 什么时候要改 | 怎么改 |
| --- | --- | --- |
| `README.md` | 端点数 / 表数 / 版本号 / 能力边界变化 | 手工改，跑一遍对账脚本核对数字 |
| `doc/openapi.yaml` | 增删端点、改权限码 | 手工改 + `validate-openapi.sh` |
| `doc/database_schema.md` | 增删表、改权限目录 | 手工改，**表结构以迁移为准** |
| `modify/<日期>-<主题>.md` | 使用手册内容变化 | 改 Markdown 后跑 `build_manual_pdf.py` 重新导出 PDF |
| `CHANGELOG.md` | 每个发版 | 手工写 |
| `VERSION` 分散在 7 处 | 发版 | `backend/pom.xml` / `web/package.json` / `worker/__init__.py` / `application.yml` / `helm/Chart.yaml` + `values.yaml` / `openapi.yaml` / `e2e/package.json` |

> **PDF 要能重新导出**：改完 `modify/` 下的手册 Markdown 后，
> 必须跑 `python scripts/build_manual_pdf.py` —— PDF 是生成物，**不要手工编辑**。
