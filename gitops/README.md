# GitOps 多环境自动部署

基于 ArgoCD + Helm 的 GitOps 流程，实现 dev / staging / prod 三环境自动同步。

## 架构

```
Git 仓库
  ├── helm/edam/         # Helm Chart
  ├── gitops/
  │   ├── argocd/        # ArgoCD 配置
  │   │   ├── projects/  # AppProject
  │   │   └── applications/  # 3 个 Application
  │   └── overlays/      # 环境特定配置
  │       ├── dev/
  │       ├── staging/
  │       └── prod/
  └── docs/

ArgoCD（集群内）
  ↓ 监听
Helm 渲染 → 部署到 K8s
```

## 三个环境

| 环境 | 命名空间 | 同步方式 | 副本数 | 用途 |
| --- | --- | --- | --- | --- |
| dev | edam-dev | 自动（PR 合并即同步） | 1 | 开发自测 |
| staging | edam-staging | 手动 | 2 | 集成测试 / 预发布 |
| prod | edam | 手动（tag 触发） | 5 | 生产环境 |

## 部署流程

### 1. 开发环境自动同步

```bash
# 1. 开发者提交 PR
git push origin feature/new-feature

# 2. CI 通过后合入 main
gh pr merge --squash

# 3. ArgoCD 自动检测变更（3 分钟内）
# 4. 自动部署到 dev 环境
```

### 2. 预发布手动同步

```bash
# 1. 通过 ArgoCD UI 触发
argocd app sync edam-staging

# 或 GitOps 方式
# 修改 gitops/overlays/staging/values.yaml
git commit -m "chore: 更新 staging 配置"
git push
# 然后在 ArgoCD UI 点击 Sync
```

### 3. 生产发布（多级审批）

```bash
# 1. 打 tag
git tag v3.1.0
git push origin v3.1.0

# 2. CI 自动构建多架构镜像

# 3. 修改 applications/edam-prod.yaml 的 targetRevision
# targetRevision: v3.1.0
# backend.image.tag: v3.1.0

# 4. 提交 PR
gh pr create

# 5. 多级审批：
#    - 技术负责人 Review
#    - SRE 负责人 Review
#    - 安全审核

# 6. 合入后 SRE 在 ArgoCD UI 手动 Sync
argocd app sync edam-prod

# 7. 监控 + 验证
```

## 密钥管理

通过 External Secrets Operator 从 Vault 同步：

```
Vault (dev/staging/prod)
  ↓ Kubernetes ServiceAccount 认证
ExternalSecret CRD
  ↓ 1h 刷新
K8s Secret
  ↓ Pod 挂载
应用
```

**生产密钥必须从 Vault 同步，禁止明文存储在 Git！**

## 安装 ArgoCD

```bash
# 1. 创建 namespace
kubectl create namespace argocd

# 2. 安装 ArgoCD
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 3. 安装 CLI
brew install argocd  # macOS
# 或
curl -sSL -o /usr/local/bin/argocd https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64

# 4. 登录
argocd login <argocd-server>

# 5. 应用本仓库配置
kubectl apply -f gitops/argocd/projects/edam-appproject.yaml
kubectl apply -f gitops/argocd/applications/edam-dev.yaml
kubectl apply -f gitops/argocd/applications/edam-staging.yaml
kubectl apply -f gitops/argocd/applications/edam-prod.yaml
```

## 日常运维

```bash
# 查看应用状态
argocd app list

# 同步应用
argocd app sync edam-dev
argocd app sync edam-staging

# 查看差异
argocd app diff edam-staging

# 回滚
argocd app rollback edam-prod

# 查看历史
argocd app history edam-prod

# 终端 UI
argocd app list -o yaml
```

## 通知集成

配置 ArgoCD Notifications（Slack / 钉钉 / 企业微信）：

```yaml
# argocd-notifications-cm ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: argocd-notifications-cm
  namespace: argocd
data:
  service.slack: |
    token: $slack-token
  trigger.sync-failed: |
    - send: [slack-channel] {app.metadata.name} sync failed
  trigger.on-deployed: |
    - send: [slack-channel] {app.metadata.name} deployed
```

## 安全最佳实践

1. **密钥不存 Git**：通过 External Secrets 从 Vault 同步
2. **最小权限**：AppProject 限定可访问的命名空间
3. **变更窗口**：syncWindows 限制非工作时间同步
4. **多级审批**：生产环境必须手动同步
5. **审计日志**：ArgoCD 完整操作日志
6. **回滚机制**：每个 Application 保留 10-20 个历史版本

## 故障排查

```bash
# 1. 查看应用状态
kubectl get application -n argocd
argocd app get edam-prod

# 2. 查看同步错误
argocd app manifests edam-prod
kubectl logs -n argocd -l app.kubernetes.io/name=argocd-application-controller

# 3. 强制覆盖
argocd app sync edam-prod --force
```

## 参考

- [ArgoCD 官方文档](https://argo-cd.readthedocs.io/)
- [External Secrets Operator](https://external-secrets.io/)
- [GitOps 原则](https://opengitops.dev/)