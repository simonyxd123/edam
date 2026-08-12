# Helm Chart - edam

企业全格式数字资产防泄密系统（v3.1）的 Kubernetes 部署 Chart。

## 前置要求

- Kubernetes 1.24+
- Helm 3.10+
- 已安装的依赖服务（推荐使用 Bitnami Charts 或自建）：
  - MySQL 8.0
  - Redis Cluster
  - MinIO
  - RabbitMQ
  - Vault
  - Elasticsearch

## 快速部署

### 1. 部署依赖

```bash
# 创建 namespace
kubectl create namespace edam

# 部署依赖（使用 Bitnami Charts 作为示例）
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install mysql bitnami/mysql --namespace edam --version 11.x.x
helm install redis bitnami/redis --namespace edam --version 20.x.x
helm install minio bitnami/minio --namespace edam --version 14.x.x
helm install rabbitmq bitnami/rabbitmq --namespace edam --version 14.x.x
```

### 2. 部署应用

```bash
# 基础部署
helm install edam ./helm/edam --namespace edam

# 自定义配置
helm install edam ./helm/edam --namespace edam \
  --set backend.replicaCount=5 \
  --set backend.resources.limits.cpu=4000m

# 使用外部 values 文件
helm install edam ./helm/edam --namespace edam -f custom-values.yaml
```

### 3. 验证

```bash
# 查看 Pod 状态
kubectl get pods -n edam

# 查看 Service
kubectl get svc -n edam

# 查看 Ingress
kubectl get ingress -n edam

# 查看日志
kubectl logs -f -n edam -l app.kubernetes.io/component=backend

# 端口转发调试
kubectl port-forward -n edam svc/edam-edam-backend 8080:8080
```

## 关键配置

### 后端资源（生产建议）

```yaml
backend:
  replicaCount: 5
  resources:
    requests:
      cpu: 2000m
      memory: 4Gi
    limits:
      cpu: 4000m
      memory: 8Gi
  autoscaling:
    minReplicas: 5
    maxReplicas: 20
    targetCPUUtilizationPercentage: 60
```

### 密钥管理

```bash
# 1. 先用 Vault 创建密钥
vault kv put secret/edam/db password=xxx
vault kv put secret/edam/jwt secret=xxx

# 2. 通过 External Secrets Operator 同步
# 或直接在部署时注入
helm install edam ./helm/edam \
  --set secret.dbPassword=$(echo -n "xxx" | base64) \
  --set secret.jwtSecret=$(openssl rand -base64 32)
```

### 镜像仓库

```bash
# 使用私有镜像仓库
helm install edam ./helm/edam \
  --set global.imageRegistry=registry.example.com \
  --set global.imagePullSecrets[0].name=my-registry-secret
```

## 升级

```bash
# 查看变更
helm diff upgrade edam ./helm/edam --namespace edam

# 执行升级
helm upgrade edam ./helm/edam --namespace edam

# 回滚
helm history edam --namespace edam
helm rollback edam 1 --namespace edam
```

## 卸载

```bash
helm uninstall edam --namespace edam
```

## 监控

Chart 包含 ServiceMonitor 资源（前提：集群已安装 Prometheus Operator）。

```bash
# 查看 ServiceMonitor
kubectl get servicemonitor -n edam

# 访问 Grafana
# 通过 Prometheus 数据源查看 edam 命名空间下的指标
```

## 安全特性

- ✅ Pod Security Context（runAsNonRoot、readOnlyRootFilesystem）
- ✅ 资源限制
- ✅ 健康检查（liveness / readiness）
- ✅ PodDisruptionBudget（保证最小可用）
- ✅ HPA 自动伸缩
- ✅ NetworkPolicy（建议通过 Calico 启用）
- ✅ Secrets 注入（从 Vault）
- ✅ Ingress TLS（cert-manager 自动签发）

## 自定义 Values 示例

参考 `values.yaml` 默认值。可在生产环境调整：

- 副本数（replicaCount）
- 资源限制（resources）
- 自动伸缩阈值（autoscaling）
- 镜像版本（image.tag）
- 密钥（secret.*）
- Ingress 配置（ingress.*）