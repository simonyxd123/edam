# ArgoCD 多环境 GitOps 部署指南（v3.3 W-12）

- 文档版本：v1.0
- 编制日期：2026-08-28
- 关联：v3.2 §9.3 高可用 + §11.1 实施计划

---

## 一、架构

```
┌────────────────────────────────────────────────────────────────┐
│                       GitHub / GitLab                             │
│  - helm/edam/values.yaml (default)                               │
│  - helm/edam/values-dev.yaml / values-staging.yaml / values-prod.yaml│
└────────┬───────────────────────────────────────────────────────┘
         │ webhook on push to main
         ▼
┌────────────────────────────────────────────────────────────────┐
│                  ArgoCD Server                                    │
│  - ApplicationSet 自动生成 3 个 Application                      │
│  - 检测 helm/ 目录变更                                            │
│  - 自动同步到 K8s                                                  │
└────────┬───────────────────────────────────────────────────────┘
         │
         ├──> dev      (namespace: edam-dev, replicas=1, auto-sync)
         ├──> staging  (namespace: edam-staging, replicas=2, auto-sync)
         └──> prod     (namespace: edam-prod, replicas=5, manual-approve)
```

---

## 二、部署步骤

### 2.1 安装 ArgoCD

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

### 2.2 创建 AppProject

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: edam
  namespace: argocd
spec:
  description: EDAM 项目
  sourceRepos:
    - https://github.com/example/edam.git
  destinations:
    - namespace: edam-*
    - server: '*'
  clusterResourceWhitelist:
    - group: '*'
      kind: '*'
```

### 2.3 创建 ApplicationSet

参见 `gitops/argocd/applicationset.yaml`

### 2.4 values-*.yaml 多环境配置

```yaml
# values-dev.yaml
env: dev
replicaCount: 1
logLevel: DEBUG
ingress:
  enabled: true
  host: dev.edam.example.com
secrets:
  dbPassword: dev-only-password
```

```yaml
# values-staging.yaml
env: staging
replicaCount: 2
logLevel: INFO
ingress:
  enabled: true
  host: staging.edam.example.com
secrets:
  dbPassword: $VAULT_STAGING_DB_PASSWORD
```

```yaml
# values-prod.yaml
env: prod
replicaCount: 5
logLevel: WARN
ingress:
  enabled: true
  host: api.edam.example.com
secrets:
  dbPassword: $VAULT_PROD_DB_PASSWORD
autoscaling:
  enabled: true
  minReplicas: 5
  maxReplicas: 50
```

---

## 三、镜像升级流程

### 3.1 开发流程

```
1. 开发者推送代码 → CI 构建镜像 → 推送到 GHCR
2. 更新 helm/edam/values.yaml 中的 image.tag
3. 提交 Git → webhook 触发 ArgoCD
4. ArgoCD 检测到变更 → 自动同步 dev
5. 5 分钟后健康检查通过 → 同步 staging
6. 人工确认 → 同步 prod
```

### 3.2 镜像标签策略

| 环境 | 标签 | 更新方式 |
| --- | --- | --- |
| dev | `latest` + commit SHA | 自动 |
| staging | `v1.2.0-rc1` | 自动 |
| prod | `v1.2.0` | 人工（Git Tag）|

### 3.3 回滚流程

```bash
# ArgoCD CLI
argocd app rollback edam-prod

# 或 Git 方式
git revert <commit-sha>
git push
```

---

## 四、安全加固

### 4.1 镜像签名

```bash
# Cosign 签名（Sigstore）
cosign sign --key cosign.key ghcr.io/example/edam-backend:v1.2.0
cosign verify --key cosign.pub ghcr.io/example/edam-backend:v1.2.0
```

### 4.2 SBOM 集成

```yaml
# GitHub Action: anchore/sbom-action
- name: Generate SBOM
  uses: anchore/sbom-action@v0
  with:
    artifact: ghcr.io/example/edam-backend:v1.2.0
```

### 4.3 Policy as Code（OPA）

```rego
package main

deny[msg] {
    input.spec.containers[_].securityContext.privileged == true
    msg := "Privileged container not allowed"
}
```

---

## 五、监控与告警

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| ArgoCD sync 失败 | > 5/min | P1 |
| 应用健康检查失败 | > 3/min | P1 |
| Git webhook 失败 | 任何 | P2 |
| ImagePull 失败 | > 5/min | P2 |
| OutOfSync 应用数 | > 3 | P2 |

---

## 六、与 v3.2 DevOps 整合

| 组件 | 集成 |
| --- | --- |
| GitHub Actions | 构建 + 推送镜像 + SBOM |
| GitLab CI | 构建 + 推送 + 漏洞扫描 |
| Helm Chart | helm/edam/（已有）|
| Vault External Secrets | K8s Secret 注入 |
| Prometheus | 应用指标采集 |
| Grafana | 部署可视化 |

---

## 七、相关文档

- `helm/edam/` — Helm Chart
- `.github/workflows/` — CI 工作流
- `monitoring/` — Prometheus + Grafana

---

**ArgoCD GitOps 部署指南完成。** 等待团队按 2 周节奏实施。