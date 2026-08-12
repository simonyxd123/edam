# ADR-0007：Kubernetes 部署（Helm vs Kustomize）

- **状态**：✅ 已接受
- **日期**：2026-08-12

## 上下文

本系统将部署在生产 K8s 集群（3 个 master + 6+ worker 节点），需要管理：
- 后端 Deployment（3-10 副本）
- Worker Deployment（2+ 副本）
- Nginx Ingress / Gateway
- HPA / PDB / NetworkPolicy
- 多个环境（dev / staging / prod）

## 评估

### 方案 1：纯 YAML + Kustomize

- ✅ Kustomize 是 K8s 原生工具（kubectl 内置）
- ✅ 适合基础场景，无额外依赖
- ✅ Patch 机制优雅
- ❌ 复杂逻辑（条件判断、循环）表达力弱
- ❌ 多环境差异需维护多个 kustomization.yaml

### 方案 2：Helm 3

- ✅ 模板化 + 参数化（Go template 语法）
- ✅ 依赖管理（Chart 引用）
- ✅ Release 管理（升级/回滚）
- ✅ 生态完善（ArtifactHub、Bitnami 仓库）
- ❌ 模板逻辑复杂时不易调试
- ❌ 客户端工具链复杂（需要 helm CLI 或 helm-operator）

### 方案 3：Helm + Kustomize 混合

- Helm 渲染基础模板 + Kustomize Patch
- ✅ 兼顾灵活性和复用性
- ❌ 双工具维护成本

## 决策

**采用 Helm 3（单一方案）**。

理由：
1. **模板能力强**：条件判断、循环、变量替换等场景更易表达
2. **生态成熟**：Bitnami 仓库提供 MySQL/Redis/MinIO/RabbitMQ 等依赖 Chart
3. **Release 管理**：内置 upgrade / rollback 流程，便于运维
4. **OCI 支持**：v3.8+ 支持 OCI registry（`helm install oci://...`），便于与 CI/CD 集成
5. **团队熟悉度**：运维团队已有 Helm 经验

## Chart 结构

```
helm/edam/
├── Chart.yaml          # 元信息
├── values.yaml         # 默认配置
├── README.md           # 使用文档
└── templates/
    ├── _helpers.tpl            # 通用模板函数
    ├── deployment.yaml         # 后端 Deployment
    ├── service.yaml            # 后端 Service
    ├── worker-deployment.yaml  # Worker Deployment
    ├── ingress.yaml            # Ingress
    ├── secret.yaml             # 密钥（从 Vault 同步）
    ├── hpa.yaml                # 自动伸缩
    ├── pdb.yaml                # Pod 中断预算
    └── servicemonitor.yaml     # Prometheus 监控
```

## 部署流程

### 1. 预发布环境（自动）

```bash
# GitLab CI: develop 分支推送
helm upgrade --install edam helm/edam \
  --namespace edam-staging \
  --set backend.image.tag=$CI_COMMIT_SHORT_SHA
```

### 2. 生产环境（手动 + 多级审批）

```bash
# 1. 运维申请发布
# 2. SRE 审核（依赖、配置）
# 3. 安全审核（变更内容）
# 4. 主管批准
# 5. 执行：
helm upgrade --install edam helm/edam \
  --namespace edam \
  --values production-values.yaml
```

## 关键设计

1. **密钥管理**：所有 Secret 通过 External Secrets Operator 从 Vault 同步
2. **镜像标签**：CI Commit SHA + Semantic Version
3. **回滚**：Helm Release 保留 10 个历史版本，可一键回滚
4. **金丝雀发布**：通过 Argo Rollouts 实现（v3.2 评估）
5. **多环境隔离**：dev/staging/prod 独立 namespace + 独立 Helm Release

## 后果

- **正向**：模板化 + 生态丰富 + Release 管理
- **负向**：需要 Helm CLI 或 Operator；模板调试相对复杂
- **缓解**：CI 集成 `helm lint` 校验；使用 `helm template` 在 CI 中预览渲染结果