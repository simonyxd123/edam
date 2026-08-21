# EDAM 部署文档（dev / staging / prod 全环境）

- **文档版本**：v1.0
- **编制日期**：2026-08-29
- **适用范围**：企业全格式数字资产防泄密系统（EDAM）v3.1+
- **对应方案书**：v3.1 §11「实施计划」 + §12「部署架构」 + §13「运维保障」
- **关联文档**：
  - `helm/edam/README.md`（Helm Chart 基础说明）
  - `gitops/README.md`（GitOps 多环境自动部署）
  - `dev/README.md`（本地 docker-compose 开发环境）
  - `ARCHITECTURE.md`（系统整体架构）
  - `modify/2026-08-29-v3.4路线图.md`（13 项任务 + 36 周时间表）
  - `modify/2026-08-29-V4-01-等保测评申请执行方案.md`（合规要求）
  - `modify/2026-08-29-V4-09-应急预案演练执行方案.md`（故障应急 SOP）

---

## 一、概述

### 1.1 文档目的

本文档提供 EDAM 系统从**本地开发 → 预发布 → 生产环境**的全链路部署指南，覆盖：

- 三套环境（dev / staging / prod）的差异化配置
- Helm Chart + ArgoCD GitOps 自动化部署
- 密钥管理（Vault + External Secrets Operator）
- CI/CD 集成（GitHub Actions / GitLab CI）
- 网络与安全加固
- 灰度发布 + 回滚预案
- 故障排查与日常运维

### 1.2 读者

| 角色 | 关注章节 |
| --- | --- |
| **后端 / 前端 / 移动端开发** | 第四章（dev）+ 第十五章（故障排查）|
| **SRE / 运维** | 第五/六/七章（环境部署）+ 第十/十一/十二章 |
| **安全 / 合规** | 第八章（密钥）+ 第十一章（安全）+ 第九章（合规）|
| **架构师** | 第二/四章（环境对比 + 架构）+ 第十三章（灰度）|
| **PM / 项目经理** | 第三章（前置）+ 第十四章（流程）+ 第十六章（附录）|

### 1.3 三套环境核心差异

| 维度 | dev | staging | prod |
| --- | --- | --- | --- |
| **命名空间** | `edam-dev` | `edam-staging` | `edam` |
| **触发方式** | PR 合入 main 自动同步 | 手动 Sync（ArgoCD UI）| Tag 触发 + 多级审批 |
| **副本数（后端）** | 1 | 2 | 5（HPA 5-20）|
| **副本数（worker）** | 1 | 1 | 3 |
| **资源（后端）** | 200m / 512Mi | 500m / 1Gi | 2000m / 4Gi（limit 4C/8G）|
| **HPA** | ❌ 禁用 | ✅ 2-5 | ✅ 5-20 |
| **Ingress** | ❌ 禁用 | ✅ staging-api.example.com | ✅ api.example.com |
| **Nginx 网关** | ❌ port-forward | ✅ 1 副本 | ✅ 3 副本 |
| **TLS 证书** | — | staging-tls | edam-tls（cert-manager）|
| **密钥来源** | values.yaml 明文 | Vault 同步 | Vault 同步 |
| **SPRING_PROFILES** | dev | staging | production |
| **日志级别** | DEBUG | INFO | INFO |
| **数据库** | 本地 docker-compose | dev-mysql 独立实例 | prod-mysql 主从 |
| **镜像 tag** | HEAD | HEAD | 固定 tag（v3.x.y）|
| **预算人力** | 1 人 | 1 人 | 5 人 |

---

## 二、环境总览与架构

### 2.1 部署架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        开发者 / 运维                              │
│  git push / PR / Tag / ArgoCD UI / kubectl                       │
└────────────┬──────────────────────┬────────────────────┬─────────┘
             │                      │                    │
             ▼                      ▼                    ▼
┌────────────────────┐   ┌────────────────────┐  ┌─────────────────┐
│  GitHub Actions CI │   │ GitLab CI（备选）  │  │  ArgoCD Server  │
│  - 文档/Schema     │   │  - 构建 + 测试     │  │  - GitOps 同步  │
│  - 编译/测试       │   │  - 镜像推送         │  │  - 3 Application│
│  - 镜像构建        │   │  - 制品归档         │  │  - 自动/手动    │
│  - 安全扫描        │   │                    │  │                 │
└────────┬───────────┘   └────────┬───────────┘  └────────┬────────┘
         │                        │                       │
         ▼                        ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                镜像仓库（GHCR / Harbor）                          │
│  edam/backend:v3.x.x   edam/worker:v3.x.x   edam/web:v3.x.x   │
└────────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              Kubernetes 集群（生产：3 节点 + 多可用区）            │
│                                                                  │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐   │
│  │  dev namespace │  │staging namespace│  │  prod namespace │   │
│  │  edam-dev      │  │  edam-staging   │  │  edam           │   │
│  │  1 副本        │  │  2 副本         │  │  5-20 副本      │   │
│  └────────────────┘  └────────────────┘  └────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              共享依赖（生产）                              │  │
│  │  MySQL 主从 + Redis Cluster + MinIO + RabbitMQ + Vault   │  │
│  │  Prometheus + Grafana + Elasticsearch + Loki              │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              外部服务（按需）                                     │
│  - cert-manager（Let's Encrypt）                                │
│  - External Secrets Operator（Vault 同步）                      │
│  - Cloud Provider（AWS/Aliyun LB + Storage）                    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 镜像版本策略

| 触发 | 镜像 tag | 推送目的地 | 用途 |
| --- | --- | --- | --- |
| **PR 合入 main** | `${{ github.sha }}` + `latest` | GHCR | dev 自动同步 |
| **Tag `v*.*.*`** | `v3.1.0` + `3.1` + `latest` | GHCR | prod 多架构发布 |
| **Tag `v*.*.*-rc*`** | `v3.1.0-rc1` | GHCR | 预发布测试 |

---

## 三、前置条件

### 3.1 工具链

| 工具 | 最低版本 | 用途 |
| --- | --- | --- |
| **kubectl** | 1.24+ | K8s 命令行 |
| **Helm** | 3.10+ | Chart 管理 |
| **Docker** | 24+ | 容器构建 + 本地 dev |
| **docker-compose** | v2+ | 本地 dev 环境 |
| **ArgoCD CLI** | 2.10+ | GitOps 同步 |
| **kustomize** | 5+ | 可选 |
| **git** | 2.30+ | 代码管理 |
| **Java** | 17 | 后端编译 |
| **Python** | 3.10 | Worker |
| **Node.js** | 18 | 前端 |
| **Maven** | 3.9+ | 后端构建 |
| **mc**（MinIO Client）| latest | 对象存储验证 |

### 3.2 基础设施清单

#### 3.2.1 dev 环境（本地开发机）

| 资源 | 最低配置 |
| --- | --- |
| CPU | 4 核 |
| 内存 | 8 GB |
| 磁盘 | 50 GB SSD |
| 网络 | 100 Mbps |
| OS | macOS 12+ / Ubuntu 20.04+ / Windows 11 WSL2 |

#### 3.2.2 staging 环境（1 个 K8s 节点）

| 资源 | 最低配置 |
| --- | --- |
| CPU | 4 核 |
| 内存 | 16 GB |
| 磁盘 | 100 GB SSD |
| K8s 版本 | 1.24+ |

#### 3.2.3 生产环境（3 节点 + 多可用区）

| 资源 | 推荐配置 |
| --- | --- |
| 节点数 | ≥ 3 节点（不同可用区）|
| 单节点 CPU | 16 核 |
| 单节点内存 | 64 GB |
| 磁盘 | 500 GB SSD（系统盘） + 1 TB NVMe（数据盘）|
| K8s 版本 | 1.27+（生产稳定版）|
| CNI | Calico（NetworkPolicy 支持）|
| LB | 云厂商 LB（AWS NLB / Aliyun SLB）|
| 存储类 | 云厂商 SSD（gp3 /ESSD PL1）|
| 域名 + 证书 | api.example.com + cert-manager 自动签发 |

### 3.3 关键依赖（生产必须）

| 依赖 | 版本 | 部署方式 | 用途 |
| --- | --- | --- | --- |
| **MySQL** | 8.0 | 主从 + MHA / Orchestrator | 业务数据库 |
| **Redis Cluster** | 7.x | 6 节点（3 主 3 从）| 缓存 + 会话 |
| **MinIO** | RELEASE.2024-xx | 4 节点纠删码 | 对象存储 |
| **RabbitMQ** | 3.13+ | 3 节点镜像队列 | 异步任务 |
| **Vault** | 1.15+ | HA 模式（3 节点）| 密钥管理 |
| **Elasticsearch** | 8.x | 3 节点 | 审计 + 全文搜索 |
| **Prometheus** | 2.48+ | 2 节点 + Thanos | 指标采集 |
| **Grafana** | 10+ | HA | 监控看板 |
| **cert-manager** | 1.14+ | 2 副本 | TLS 证书 |
| **External Secrets Operator** | 0.9+ | 2 副本 | Vault 同步 |
| **ArgoCD** | 2.10+ | HA（3 副本）| GitOps |

### 3.4 镜像仓库凭证

```bash
# GHCR 登录
echo $GITHUB_TOKEN | docker login ghcr.io -u $GITHUB_USERNAME --password-stdin

# 或私有 Harbor
docker login harbor.example.com -u $HARBOR_USER -p $HARBOR_PASS
```

### 3.5 集群访问凭证

```bash
# 生产 kubeconfig（存放于 1Password / Vault）
mkdir -p ~/.kube
cp ~/Downloads/se-edam-prod-kubeconfig.yaml ~/.kube/config

# 验证
kubectl cluster-info
kubectl get nodes
```

---

## 四、dev 环境部署（本地开发 + 自测）

### 4.1 部署架构（dev）

```
本地开发机
├── docker-compose（依赖服务：MySQL/Redis/MinIO/RabbitMQ/Vault/ES/Prometheus/Grafana）
├── 本地后端（mvn spring-boot:run，连接到 docker-compose 服务）
├── 本地前端（npm run dev，Vite HMR）
└── 本地 worker（python worker/，连接到 docker-compose 服务）
```

### 4.2 快速部署步骤

#### 步骤 1：克隆代码

```bash
git clone https://github.com/example/edam.git
cd edam
```

#### 步骤 2：启动依赖服务

```bash
cd dev/

# 复制环境变量模板
cp .env.example .env

# 启动所有依赖（首次会拉取镜像，约 3-5 分钟）
docker-compose up -d

# 查看启动状态
docker-compose ps
```

**预期输出**：

```
NAME                  STATUS              PORTS
edam-mysql            Up (healthy)        0.0.0.0:3306->3306/tcp
edam-redis            Up (healthy)        0.0.0.0:6379->6379/tcp
edam-minio            Up (healthy)        0.0.0.0:9000-9001/tcp
edam-rabbitmq         Up (healthy)        0.0.0.0:5672->5672/tcp, 15672/tcp
edam-vault            Up (healthy)        0.0.0.0:8200->8200/tcp
edam-elasticsearch    Up (healthy)        0.0.0.0:9200->9200/tcp
edam-prometheus       Up                  0.0.0.0:9090->9090/tcp
edam-grafana          Up                  0.0.0.0:3000->3000/tcp
edam-prism            Up                  0.0.0.0:4010->4010/tcp
```

#### 步骤 3：初始化数据库

```bash
# MySQL 启动时自动执行迁移（首次需等待 30 秒）
docker-compose logs mysql | grep "ready for connections"

# 手动执行迁移（可选）
docker-compose exec mysql mysql -uroot -prootpass \
  -e "SHOW DATABASES;"
# 预期看到 edam 数据库
```

#### 步骤 4：启动后端

```bash
cd ../backend

# 配置环境变量
export SPRING_PROFILES_ACTIVE=dev
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_USERNAME=edam
export MYSQL_PASSWORD=edampass
export REDIS_HOST=localhost
export MINIO_ENDPOINT=http://localhost:9000
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=root

# 编译并运行
mvn clean spring-boot:run

# 预期输出
# Tomcat started on port 8080 (http)
# Started Application in 12.345 seconds
```

#### 步骤 5：启动前端

```bash
cd ../web

npm install
npm run dev

# 预期输出
# Local:   http://localhost:5173/
# Network: use --host to expose
```

#### 步骤 6：启动 Worker

```bash
cd ../worker

pip install -r requirements.txt
pip install -r requirements-dev.txt

python -m edam_worker.main

# 预期输出
# [INFO] Worker started, waiting for tasks...
```

#### 步骤 7：验证

```bash
# 后端健康检查
curl http://localhost:8080/health/live
# {"status":"UP"}

# 前端
open http://localhost:5173

# MinIO 控制台
open http://localhost:9001
# 账号: minioadmin / minioadmin

# RabbitMQ 管理
open http://localhost:15672
# 账号: edam / edampass

# Grafana
open http://localhost:3000
# 账号: admin / admin
```

### 4.3 常见调试技巧

```bash
# 查看后端日志（实时）
docker-compose logs -f mysql

# 进入容器调试
docker-compose exec mysql bash
docker-compose exec redis redis-cli

# 重启单个服务
docker-compose restart backend

# 完全重置（删除数据）
docker-compose down -v
docker-compose up -d

# 查看资源占用
docker stats
```

### 4.4 dev 环境常见问题

| 问题 | 排查 | 解决 |
| --- | --- | --- |
| 端口冲突 | `lsof -i :3306` | 修改 `docker-compose.yml` ports |
| MySQL 启动慢 | `docker-compose logs mysql` | 等待 `ready for connections` |
| Vault 锁定 | `docker-compose logs vault` | `docker-compose exec vault vault operator unseal` |
| MinIO 桶丢失 | `docker-compose logs minio-init` | 手动 `mc mb local/edam-videos` |
| 后端连接失败 | 检查 host 是否为 `localhost` | 容器内使用 `host.docker.internal` |

---

## 五、staging 环境部署（预发布 + 集成测试）

### 5.1 部署架构（staging）

```
K8s staging 集群
├── namespace: edam-staging
├── 后端：2 副本（autoscaling 2-5）
├── Worker：1 副本
├── Nginx：1 副本
├── Ingress：staging-api.example.com
└── 依赖（共享 dev/prod 集群）：MySQL(独立实例) / Redis / MinIO / Vault / ES
```

### 5.2 部署前置

#### 5.2.1 集群准备

```bash
# 确认 kubectl 上下文
kubectl config current-context
# 应为 staging-cluster

# 创建 namespace
kubectl create namespace edam-staging

# 安装依赖 Operator（如未安装）
kubectl apply -f https://raw.githubusercontent.com/cert-manager/cert-manager/v1.14.0/deploy/cert-manager.yaml
kubectl apply -f https://raw.githubusercontent.com/external-secrets/external-secrets/v0.9.0/deploy/crds.yaml
```

#### 5.2.2 Vault 配置（staging）

```bash
# 启用 Kubernetes 认证
vault auth enable kubernetes

# 配置 K8s 认证
vault write auth/kubernetes/config \
  kubernetes_host="https://kubernetes.default.svc.cluster.local"

# 创建策略
vault policy write edam-staging - <<EOF
path "secret/data/edam/*" {
  capabilities = ["read"]
}
EOF

# 创建角色（绑定 ServiceAccount）
vault write auth/kubernetes/role/edam-staging \
  bound_service_account_names=edam-backend,edam-worker \
  bound_service_account_namespaces=edam-staging \
  policies=edam-staging \
  ttl=1h
```

#### 5.2.3 写入密钥

```bash
# 数据库密码
vault kv put secret/edam/database \
  username=edam \
  password=$(openssl rand -base64 32)

# JWT Secret
vault kv put secret/edam/jwt \
  secret=$(openssl rand -base64 64)

# Vault Token
vault kv put secret/edam/vault \
  token=$(cat ~/.vault-token)
```

### 5.3 部署步骤（手动触发 ArgoCD Sync）

#### 步骤 1：应用 ArgoCD AppProject

```bash
kubectl apply -f gitops/argocd/projects/edam-appproject.yaml
```

#### 步骤 2：应用 ArgoCD Application

```bash
kubectl apply -f gitops/argocd/applications/edam-staging.yaml
```

#### 步骤 3：等待 ArgoCD 检测

```bash
# 查看应用状态
argocd app list | grep staging

# 输出：edam-staging  OutOfSync  manual
```

#### 步骤 4：手动触发同步

```bash
# CLI 方式
argocd app sync edam-staging

# 或 ArgoCD UI
# 访问 https://argo.example.com → edam-staging → Sync
```

#### 步骤 5：验证部署

```bash
# 查看 Pod
kubectl get pods -n edam-staging

# 预期输出
NAME                              READY   STATUS    RESTARTS   AGE
edam-staging-backend-xxx-yyy      1/1     Running   0          2m
edam-staging-backend-xxx-zzz      1/1     Running   0          2m
edam-staging-worker-aaa-bbb       1/1     Running   0          2m
edam-staging-nginx-ccc-ddd        1/1     Running   0          2m

# 查看 Service / Ingress
kubectl get svc,ingress -n edam-staging

# 健康检查
curl https://staging-api.example.com/health/live
# {"status":"UP"}

# 日志
kubectl logs -n edam-staging -l app.kubernetes.io/component=backend --tail=100 -f
```

### 5.4 staging 配置说明

参考 `gitops/overlays/staging/values.yaml`：

| 项 | 值 | 说明 |
| --- | --- | --- |
| `backend.replicaCount` | 2 | 中等负载 |
| `backend.resources` | 500m/1Gi | 中等规格 |
| `backend.autoscaling` | 2-5 | 启用 HPA |
| `worker.replicaCount` | 1 | 单一队列 |
| `nginx.enabled` | true | 启用网关 |
| `ingress.host` | staging-api.example.com | 测试域名 |
| `ingress.tls.secretName` | staging-tls | 测试证书 |
| `secret.*` | REPLACE_FROM_VAULT | 由 External Secrets 注入 |
| `SPRING_PROFILES_ACTIVE` | staging | 测试 profile |

### 5.5 staging 验证清单

|  | 项 | 验证方式 | 通过标准 |
| --- | --- | --- | --- |
| ☐ | 所有 Pod Running | `kubectl get pods -n edam-staging` | 全部 1/1 |
| ☐ | 健康检查 | `curl /health/live` | `{"status":"UP"}` |
| ☐ | Ingress 可达 | `curl https://staging-api.example.com/health/ready` | 200 OK |
| ☐ | 数据库连接 | `kubectl logs backend | grep "HikariPool"` | 无错误 |
| ☐ | Redis 连接 | `kubectl exec backend -- redis-cli ping` | PONG |
| ☐ | Vault 密钥同步 | `kubectl get secret -n edam-staging edam-db-secret` | 存在 |
| ☐ | RabbitMQ 队列 | `kubectl exec rabbitmq -- rabbitmqctl list_queues` | 队列创建 |
| ☐ | MinIO 桶 | `mc ls staging-minio/edam-videos` | 桶存在 |
| ☐ | 日志采集 | Kibana 查看日志 | 实时日志 |
| ☐ | 指标采集 | Prometheus targets | backend UP |

---

## 六、生产环境部署（prod）

### 6.1 部署架构（prod）

```
K8s prod 集群（3 节点 + 多可用区）
├── namespace: edam
├── 后端：5 副本（autoscaling 5-20，HPA）
├── Worker：3 副本
├── Nginx：3 副本
├── Ingress：api.example.com（cert-manager 自动签发）
├── PodDisruptionBudget：minAvailable=2
└── 依赖（HA 部署）：MySQL 主从 / Redis Cluster 6 节点 / MinIO 4 节点 / RabbitMQ 3 节点 / Vault HA / ES 3 节点
```

### 6.2 部署前置

#### 6.2.1 集群初始化

```bash
# 1. 创建 namespace
kubectl create namespace edam

# 2. 打标签（用于 NetworkPolicy / PodSelector）
kubectl label namespace edam \
  env=prod \
  app=edam \
  criticality=high

# 3. 创建 ServiceAccount（用于 Vault 认证 + Pod RBAC）
kubectl -n edam create serviceaccount edam-backend
kubectl -n edam create serviceaccount edam-worker
```

#### 6.2.2 Vault 配置（prod）

```bash
# 启用 K8s 认证
vault auth enable kubernetes

# 配置
vault write auth/kubernetes/config \
  kubernetes_host="https://kubernetes.default.svc.cluster.local"

# 创建策略（严格权限）
vault policy write edam-prod - <<EOF
# 数据库密码（只读）
path "secret/data/edam/database" {
  capabilities = ["read"]
}
# JWT Secret
path "secret/data/edam/jwt" {
  capabilities = ["read"]
}
# Vault Transit（加解密）
path "transit/encrypt/edam-*" {
  capabilities = ["create", "update"]
}
path "transit/decrypt/edam-*" {
  capabilities = ["create", "update"]
}
EOF

# 创建角色
vault write auth/kubernetes/role/edam-prod \
  bound_service_account_names=edam-backend,edam-worker \
  bound_service_account_namespaces=edam \
  policies=edam-prod \
  ttl=1h
```

#### 6.2.3 写入生产密钥

```bash
# 数据库密码（生产必须 ≥ 32 位随机）
DB_PASS=$(openssl rand -base64 32)
vault kv put secret/edam/database \
  username=edam \
  password="$DB_PASS"

# JWT Secret（生产必须 ≥ 64 位随机）
JWT_SECRET=$(openssl rand -base64 64)
vault kv put secret/edam/jwt \
  secret="$JWT_SECRET"

# 国密 SDK 密钥（V4-06 商密 SDK 集成后）
vault kv put secret/edam/gmsdk \
  app_id="..." \
  app_secret="..." \
  hsm_pin="..."

# 对象存储访问密钥
vault kv put secret/edam/minio \
  access_key="..." \
  secret_key="..."
```

#### 6.2.4 部署 External Secrets

```bash
# 应用 SecretStore + ExternalSecret
kubectl apply -f gitops/argocd/external-secrets.yaml

# 验证密钥同步
kubectl get externalsecret -n edam
# NAME                    STORE           REFRESH   STATUS
# edam-db-credentials     vault-backend   1h        SecretSynced
# edam-jwt-secret         vault-backend   24h       SecretSynced
# edam-vault-token        vault-backend   12h       SecretSynced
```

#### 6.2.5 初始化数据库

```bash
# 通过 ArgoCD Jobs 或手动执行 Flyway 迁移
kubectl -n edam create job manual-migration \
  --image=flyway/flyway:10 \
  -- flyway -url=jdbc:mysql://mysql.edam.svc:3306/edam \
            -user=edam -password="$DB_PASS" \
            -locations=filesystem:/migration migrate

# 等待完成
kubectl wait --for=condition=complete job/manual-migration -n edam --timeout=300s
```

### 6.3 部署流程（多级审批 + tag 触发）

#### 步骤 1：开发完成并合入 main

```bash
# 1. 开发者提交 PR + CI 通过
gh pr create --title "feat: 新增功能" --body "..."
# CI 跑完后由 Tech Lead / SRE / 安全三方审批

# 2. 合入 main（squash merge）
gh pr merge --squash
```

#### 步骤 2：CI 自动构建镜像（已合入 main 后）

```bash
# CI 自动构建 dev 镜像（tag = ${{ github.sha }} + latest）
# 自动推送到 GHCR
# 自动部署到 dev 环境（ArgoCD 自动同步）
```

#### 步骤 3：在 staging 验证

```bash
# 手动触发 staging 同步
argocd app sync edam-staging

# 跑集成测试
./scripts/integration-test.sh --env=staging

# 验证清单（见 5.5 节）
```

#### 步骤 4：打 tag 触发生产构建

```bash
# 1. 在 main 分支上打 tag
git tag v3.1.0
git push origin v3.1.0

# 2. CI 自动构建多架构镜像（linux/amd64 + linux/arm64）
#    Tag: v3.1.0, 3.1, latest
#    推送到 GHCR
#    打包 Helm Chart
#    创建 GitHub Release（草稿）
```

#### 步骤 5：修改 prod ArgoCD 配置

```bash
# 1. 创建分支
git checkout -b release/v3.1.0

# 2. 修改 gitops/argocd/applications/edam-prod.yaml
#    targetRevision: v3.1.0
#    backend.image.tag: v3.1.0
#    worker.image.tag: v3.1.0

# 3. 提交 PR（需多级审批）
git add gitops/argocd/applications/edam-prod.yaml
git commit -m "chore: bump prod to v3.1.0"
gh pr create --title "release: prod v3.1.0" \
  --body "Release v3.1.0 to production. See CHANGELOG.md"
```

#### 步骤 6：多级审批

| 角色 | 审批内容 | 通过标准 |
| --- | --- | --- |
| **Tech Lead** | 代码 review + 单元测试 | ✅ CI 全绿 + 2 名 reviewer |
| **SRE** | 镜像扫描 + Helm diff | ✅ Trivy 0 critical + diff 可接受 |
| **安全审核** | 密钥 + 配置 + 漏洞 | ✅ 无新增高危 + 密钥未泄露 |
| **PM** | 业务对齐 + 变更窗口 | ✅ 业务低峰期 + 已通知 |

#### 步骤 7：合入后 SRE 手动触发同步

```bash
# 1. ArgoCD UI 验证配置差异
argocd app diff edam-prod

# 2. 手动 Sync（启用 ServerSideApply）
argocd app sync edam-prod --prune --server-side

# 3. 观察同步进度
argocd app watch edam-prod
```

#### 步骤 8：监控部署过程

```bash
# Pod 滚动更新（maxSurge=1, maxUnavailable=0）
kubectl get pods -n edam -w

# 健康检查
kubectl get pods -n edam -o json | jq -r '.items[] | "\(.metadata.name) \(.status.containerStatuses[].ready)"'

# HPA 状态
kubectl get hpa -n edam

# 实时日志（关键 Pod）
kubectl logs -n edam -l app.kubernetes.io/component=backend --tail=100 -f
```

#### 步骤 9：生产验证

| 项 | 验证方式 | 通过标准 |
| --- | --- | --- |
| 所有 Pod 1/1 | `kubectl get pods -n edam` | 5/5 后端 |
| 健康检查 | `curl https://api.example.com/health/live` | `{"status":"UP"}` |
| 数据库连接 | 业务 API 调用 | 200 OK |
| Redis 缓存 | 业务 API 调用 | 命中 |
| 异步队列 | RabbitMQ UI | 队列消费 |
| Vault 密钥 | `kubectl get secret -n edam` | 全部存在 |
| HPA 副本数 | `kubectl get hpa` | 满足 minReplicas |
| Ingress TLS | 浏览器访问 https://api.example.com | 证书有效 |
| 监控指标 | Prometheus | backend UP |
| 业务核心场景 | 手工 + 自动化测试 | 全部通过 |

### 6.4 prod 配置详解

参考 `gitops/overlays/prod/values.yaml`：

| 项 | 值 | 生产考量 |
| --- | --- | --- |
| `backend.replicaCount` | 5 | 高可用 + 滚动更新 |
| `backend.resources.requests` | 2000m / 4Gi | 保证资源 |
| `backend.resources.limits` | 4000m / 8Gi | 防止 OOM 拖累节点 |
| `backend.autoscaling` | 5-20 | 弹性 + 防爆 |
| `backend.autoscaling.targetCPU` | 60 | 提前扩容 |
| `backend.autoscaling.targetMemory` | 80 | 提前扩容 |
| `backend.podDisruptionBudget.minAvailable` | 2 | 保证 ≥ 2 副本可用 |
| `backend.env.SERVER_TOMCAT_MAX_THREADS` | 400 | 高并发 |
| `worker.replicaCount` | 3 | 并行处理 |
| `worker.resources` | 1000m / 2Gi | 中等规格 |
| `nginx.replicaCount` | 3 | 网关冗余 |
| `nginx.resources.limits` | 2000m / 2Gi | 防止瓶颈 |
| `ingress.annotations.rate-limit` | 100 rps | 抗 DDoS |
| `ingress.tls.secretName` | edam-tls | cert-manager 自动签发 |

### 6.5 生产发布窗口

| 时段 | 是否可发布 | 备注 |
| --- | --- | --- |
| 工作日 09:00-18:00 | ❌ 禁止 | 业务高峰 |
| 工作日 18:00-22:00 | ⚠️ 慎用 | 低峰 |
| 工作日 22:00-次日 09:00 | ✅ 推荐 | 维护窗口 |
| 周末 00:00-06:00 | ✅ 推荐 | 低峰 |
| 节假日 | ⚠️ 慎用 | 需业务方确认 |
| 重大活动前 24h | ❌ 禁止 | 风险高 |

**默认发布窗口**：每周二/四 22:00 - 次日 02:00（经业务方确认）

### 6.6 生产回滚预案

#### 6.6.1 ArgoCD 一键回滚

```bash
# 查看历史
argocd app history edam-prod

# 输出
ID  DATE                           REVISION
8   2026-08-29 22:15:00 +0800 CST  v3.1.0
7   2026-08-22 22:10:00 +0800 CST  v3.0.5
6   2026-08-15 22:08:00 +0800 CST  v3.0.4

# 回滚到上一版本
argocd app rollback edam-prod

# 回滚到指定版本
argocd app rollback edam-prod --id 7
```

#### 6.6.2 Helm 回滚（备选）

```bash
# 查看历史
helm history edam -n edam

# 回滚到上一版本
helm rollback edam -n edam

# 回滚到指定版本
helm rollback edam 3 -n edam
```

#### 6.6.3 数据库回滚（慎重）

```bash
# 仅在数据迁移失败时使用
# 1. 停止应用
kubectl scale deployment edam-edam-backend --replicas=0 -n edam

# 2. 恢复数据库快照
kubectl exec -n edam mysql-master -- mysql -uroot -p"$ROOT_PASS" \
  -e "DROP DATABASE edam; CREATE DATABASE edam;"

# 3. 导入备份
kubectl exec -i -n edam mysql-master -- mysql -uroot -p"$ROOT_PASS" edam \
  < backup-20260829.sql

# 4. 回滚应用
argocd app rollback edam-prod --id 7

# 5. 扩容验证
kubectl scale deployment edam-edam-backend --replicas=5 -n edam
```

#### 6.6.4 回滚决策矩阵

| 触发 | 回滚响应 | 负责人 |
| --- | --- | --- |
| 启动失败（CrashLoopBackOff）| ArgoCD 自动回滚（self-heal）| ArgoCD |
| 启动成功但健康检查失败 | 手动回滚（≤ 5min）| SRE |
| 启动成功但业务异常 | 手动回滚（≤ 30min）| SRE + Tech Lead |
| 数据迁移失败 | 暂停 + 数据库回滚 | SRE + DBA |
| 严重安全漏洞发现 | 立即回滚 + 安全响应 | SRE + 安全 |
| 用户大量投诉 | 立即回滚 | SRE + PM |

---

## 七、灰度发布策略

### 7.1 三种灰度发布模式

| 模式 | 适用场景 | 复杂度 | 回滚速度 |
| --- | --- | --- | --- |
| **蓝绿发布**（Blue/Green）| 大版本升级 | 中 | < 1min |
| **金丝雀发布**（Canary）| 新功能验证 | 中 | < 5min |
| **滚动发布**（Rolling Update）| 常规升级 | 低 | < 10min |

### 7.2 蓝绿发布（生产推荐）

#### 7.2.1 架构

```
                  Ingress (api.example.com)
                          │
                          ▼
                  Service (selector: version=blue)
                  ┌───────┴───────┐
                  ▼               ▼
            blue-v3.0          green-v3.1（待机）
            (5 副本)           (5 副本)
```

#### 7.2.2 部署流程

```bash
# 1. 部署 green（新版本）
kubectl apply -f helm/overlays/green/values.yaml

# 2. green 健康检查通过后切换流量
kubectl patch service edam-edam-backend -n edam \
  -p '{"spec":{"selector":{"version":"green"}}}'

# 3. 观察 30 分钟
# 4. 若异常，切回 blue
kubectl patch service edam-edam-backend -n edam \
  -p '{"spec":{"selector":{"version":"blue"}}}'

# 5. 若正常，下线 blue
kubectl delete deployment -l version=blue -n edam
```

### 7.3 金丝雀发布（推荐 Argo Rollouts）

#### 7.3.1 架构

```yaml
# argocd-application-rollout.yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: edam-backend
spec:
  replicas: 5
  strategy:
    canary:
      steps:
        - setWeight: 10   # 10% 流量
        - pause: {duration: 30m}
        - setWeight: 30
        - pause: {duration: 30m}
        - setWeight: 60
        - pause: {duration: 30m}
        - setWeight: 100
```

#### 7.3.2 部署流程

```bash
# 1. 应用 Argo Rollouts
kubectl apply -f gitops/argocd/rollouts/edam-backend.yaml

# 2. 触发金丝雀
kubectl argo rollouts set image edam-backend \
  backend=ghcr.io/example/edam/backend:v3.1.0

# 3. 监控（自动推进 / 手动 pause）
kubectl argo rollouts get rollout edam-backend --watch

# 4. 异常时中止 + 回滚
kubectl argo rollouts abort edam-backend
kubectl argo rollouts undo edam-backend
```

### 7.4 滚动发布（默认）

```yaml
# helm/edam/values.yaml
backend:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1          # 多 1 个
      maxUnavailable: 0    # 不少 1 个（零中断）
```

---

## 八、密钥管理

### 8.1 密钥分级

| 级别 | 示例 | 存储 | 轮换周期 |
| --- | --- | --- | --- |
| **L4 最高** | 数据库主密码、Vault unseal key | HashiCorp Vault + HSM | 90 天 |
| **L3 高** | JWT Secret、商密 SDK 密钥 | HashiCorp Vault | 180 天 |
| **L2 中** | MinIO 访问密钥、RabbitMQ 密码 | HashiCorp Vault | 365 天 |
| **L1 低** | dev 测试密钥、占位密钥 | Helm values.yaml 明文 | 不轮换 |

### 8.2 Vault 部署

#### 8.2.1 HA 部署（生产）

```bash
# 使用 Bitnami Vault Helm Chart
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install vault bitnami/vault \
  --namespace vault \
  --create-namespace \
  --set "server.ha.enabled=true" \
  --set "server.ha.replicas=3" \
  --set "server.ha.config=..."
```

#### 8.2.2 自动 Unseal

```bash
# 使用 AWS KMS / Azure Key Vault / GCP KMS 自动 unseal
vault operator init \
  -recovery-shares=5 \
  -recovery-threshold=3 \
  -seal-awskms=region=us-east-1,kms_key_id=...
```

### 8.3 External Secrets Operator

#### 8.3.1 工作流程

```
Vault
  ↓ Kubernetes ServiceAccount 认证
ExternalSecret CRD
  ↓ refreshInterval（1h/24h/12h）
K8s Secret
  ↓ Pod 挂载（环境变量 / volume）
应用容器
```

#### 8.3.2 SecretStore 配置

```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
  name: vault-backend
  namespace: edam
spec:
  provider:
    vault:
      server: "http://vault.vault.svc.cluster.local:8200"
      path: "secret"
      version: "v2"
      auth:
        kubernetes:
          mountPath: "kubernetes"
          role: "edam-prod"
          serviceAccountRef:
            name: "edam-backend"
```

#### 8.3.3 ExternalSecret 配置

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: edam-db-credentials
  namespace: edam
spec:
  refreshInterval: 1h         # 每小时刷新
  secretStoreRef:
    name: vault-backend
    kind: SecretStore
  target:
    name: edam-db-secret
    creationPolicy: Owner
  data:
    - secretKey: db-password
      remoteRef:
        key: edam/database
        property: password
```

### 8.4 密钥轮换 SOP

| 密钥 | 轮换步骤 | 影响 |
| --- | --- | --- |
| **DB 密码** | 1. 新密码写入 Vault<br>2. 修改 MySQL 用户密码<br>3. ESO 自动同步到 K8s<br>4. Pod 自动重启（滚动）| 短暂连接抖动 |
| **JWT Secret** | 1. 双写期（24h，新旧都接受）<br>2. Vault 切换新 Secret<br>3. ESO 同步<br>4. 下线旧 Secret| 旧 token 失效，用户需重新登录 |
| **Vault Token** | 1. 创建新 ServiceAccount Token<br>2. Vault 写入新 token<br>3. ESO 同步<br>4. Pod 重启 | 无感知 |
| **商密 SDK 密钥** | 1. 新密钥写入 HSM<br>2. Vault Transit 切换<br>3. 算法路由层重新加载<br>4. 数据重加密（异步）| 异步无感知 |

---

## 九、网络与安全

### 9.1 Ingress + TLS

#### 9.1.1 cert-manager 自动签发

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: security@example.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            class: nginx
```

#### 9.1.2 Ingress 配置

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: edam-prod
  namespace: edam
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/proxy-body-size: "500m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "300"
    nginx.ingress.kubernetes.io/rate-limit: "100"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - api.example.com
      secretName: edam-tls
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: edam-edam-backend
                port:
                  number: 8080
```

### 9.2 NetworkPolicy

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: edam-backend
  namespace: edam
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/component: backend
  policyTypes:
    - Ingress
    - Egress
  ingress:
    # 允许来自 Nginx 的流量
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/component: nginx
      ports:
        - protocol: TCP
          port: 8080
    # 允许 Prometheus 抓取
    - from:
        - namespaceSelector:
            matchLabels:
              name: monitoring
      ports:
        - protocol: TCP
          port: 8080
  egress:
    # 允许访问 MySQL
    - to:
        - podSelector:
            matchLabels:
              app: mysql
      ports:
        - protocol: TCP
          port: 3306
    # 允许访问 Redis
    - to:
        - podSelector:
            matchLabels:
              app: redis
      ports:
        - protocol: TCP
          port: 6379
    # 允许 DNS
    - to:
        - namespaceSelector: {}
      ports:
        - protocol: UDP
          port: 53
```

### 9.3 Pod Security Standards

依据 `helm/edam/values.yaml`：

| 项 | 配置 | 说明 |
| --- | --- | --- |
| `podSecurityContext.runAsNonRoot` | true | 禁止 root |
| `podSecurityContext.fsGroup` | 1000 | 文件系统组 |
| `podSecurityContext.seccompProfile` | RuntimeDefault | 系统调用限制 |
| `securityContext.runAsUser` | 1000 | 固定用户 |
| `securityContext.readOnlyRootFilesystem` | true | 只读根文件系统 |
| `securityContext.allowPrivilegeEscalation` | false | 禁止提权 |
| `securityContext.capabilities.drop` | ALL | 丢弃所有 capabilities |

### 9.4 WAF / DDoS 防护

- **云厂商 WAF**：AWS WAF / Aliyun WAF
- **速率限制**：Ingress `rate-limit: 100`
- **CC 防护**：云厂商 Anti-DDoS
- **IP 黑名单**：动态封禁异常 IP

---

## 十、监控与告警

### 10.1 Prometheus 指标采集

```yaml
# helm/edam/values.yaml
backend:
  serviceMonitor:
    enabled: true
    interval: 30s
    path: /actuator/prometheus
```

### 10.2 关键指标

| 指标 | 来源 | 告警阈值 |
| --- | --- | --- |
| **JVM 内存使用率** | `jvm_memory_used_bytes / jvm_memory_max_bytes` | > 80% |
| **GC 暂停时间** | `jvm_gc_pause_seconds` P99 | > 500ms |
| **HTTP 请求 P99** | `http_server_requests_seconds` P99 | > 1s |
| **HTTP 错误率** | `http_server_requests_seconds_count{status=~"5.."}` | > 1% |
| **Pod CPU** | `container_cpu_usage_seconds_total` | > 80% |
| **Pod 内存** | `container_memory_usage_bytes` | > 80% limit |
| **HikariCP 连接** | `hikaricp_connections_active` | > 80% max |
| **RabbitMQ 队列深度** | `rabbitmq_queue_messages_ready` | > 10000 |
| **Redis 命中率** | `redis_keyspace_hits / (hits + misses)` | < 80% |
| **Vault Token TTL** | `vault_token_ttl_seconds` | < 1h |

### 10.3 告警规则示例

```yaml
groups:
  - name: edam-prod-alerts
    rules:
      - alert: BackendPodCrashLooping
        expr: rate(kube_pod_container_status_restarts_total[15m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Backend Pod 频繁重启"

      - alert: HighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{namespace="edam",status=~"5.."}[5m]))
          / sum(rate(http_server_requests_seconds_count{namespace="edam"}[5m])) > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "HTTP 5xx 错误率超过 1%"

      - alert: HPAAtMaxCapacity
        expr: kube_horizontalpodautoscaler_status_current_replicas{namespace="edam"} == kube_horizontalpodautoscaler_spec_max_replicas{namespace="edam"}
        for: 10m
        labels:
          severity: warning
```

### 10.4 告警渠道

| 等级 | 渠道 | 通知人 |
| --- | --- | --- |
| **P0 紧急** | 电话 + 短信 + 钉钉 @所有人 | CTO + 全员 |
| **P1 严重** | 钉钉群 + 短信 | SRE + 模块负责人 |
| **P2 一般** | 钉钉群 | 模块负责人 |
| **P3 轻微** | 邮件 | 团队 |

---

## 十一、存储与备份

### 11.1 存储类

| 用途 | 存储类 | 容量 | IOPS |
| --- | --- | --- | --- |
| **MySQL** | ssd-gp3 | 500 GB | 3000 |
| **MinIO** | ssd-gp3 | 5 TB | 3000 |
| **Elasticsearch** | ssd-gp3 | 1 TB | 3000 |
| **RabbitMQ** | standard | 50 GB | — |
| **Vault** | standard | 10 GB | — |

### 11.2 MySQL 主从 + 备份

```bash
# 1. 每日全量备份（凌晨 3 点）
mysqldump --single-transaction --master-data=2 \
  -h mysql-master -u backup -p"$BACKUP_PASS" \
  --all-databases | gzip > /backup/edam-$(date +%Y%m%d).sql.gz

# 2. 保留策略：7 天 / 4 周 / 12 月 / 5 年
# 3. 上传到 OSS / S3
aws s3 cp /backup/edam-20260829.sql.gz s3://edam-backups/mysql/

# 4. 验证（每日）
mysql -h mysql-test -u root -p"$TEST_PASS" \
  -e "SELECT COUNT(*) FROM edam.sys_user" < /backup/edam-20260829.sql
```

### 11.3 MinIO 跨区域复制

```bash
# 生产 → 灾备（异步复制）
mc admin replicate add prod-minio/edam-videos \
  --remote-bucket https://dr-minio/edam-videos \
  --replicate "delete,delete-marker,replica,existing-objects"
```

### 11.4 ES WORM（5 年留存）

依据 `modify/2026-08-29-V4-09-应急预案演练执行方案.md` 合规要求：

```bash
# 审计日志 WORM（write once read many）
# 保留 5 年（诉讼时效）
curl -X PUT "https://es.edam/_ilm/policy/audit-worm" -H 'Content-Type: application/json' -d '{
  "policy": {
    "phases": {
      "hot": {"actions": {"rollover": {"max_age": "30d"}}},
      "warm": {"min_age": "30d", "actions": {"shrink": {"number_of_shards": 1}}},
      "cold": {"min_age": "90d", "actions": {"freeze": {}}},
      "frozen": {"min_age": "365d", "actions": {"searchable_snapshot": {}}},
      "delete": {"min_age": "1825d", "actions": {"delete": {}}}
    }
  }
}'
```

---

## 十二、CI/CD 集成

### 12.1 GitHub Actions Workflow

参考 `.github/workflows/ci.yml` + `.github/workflows/release.yml`：

| Workflow | 触发 | 任务 |
| --- | --- | --- |
| `ci.yml` | push / PR | 文档 lint + DB 迁移 + 后端编译 + Worker 测试 + 前端构建 + 镜像构建 + 安全扫描 |
| `release.yml` | tag `v*.*.*` | 多架构镜像（amd64 + arm64）+ Helm 打包 + GitHub Release |
| `perf.yml` | 定时 / 手动 | k6 压测 |
| `security-scan.yml` | 定时 | 漏洞扫描 |

### 12.2 CI 流程图

```
PR / push
    ↓
[docs-lint] OpenAPI + drawio + Helm lint + Controller 覆盖率
    ↓
[db-migration] Flyway 校验
    ↓
[backend-build] Maven compile + test + package
    ↓
[worker-test] pytest + coverage
    ↓
[frontend-build] npm ci + lint + test + build
    ↓
[docker-build] 仅 main 分支
    ↓
[security-scan] Trivy + GitLeaks
    ↓
[release] 仅 tag 触发（多架构 + Helm + Release）
```

### 12.3 部署链路总览

```
开发 → CI（push/PR）→ 镜像构建（$sha + latest）→ GHCR
   ↓
GitOps（ArgoCD 检测）→ dev 自动同步
   ↓
手动（ArgoCD UI / CLI）→ staging 同步
   ↓
Tag（v*.*.*）→ CI（多架构 + Release）→ 镜像 tag（v3.x.y）
   ↓
修改 prod Application → 多级审批 → 合入
   ↓
手动 Sync（ArgoCD）→ 滚动更新 / 蓝绿 / 金丝雀
   ↓
监控验证 → 完成
```

---

## 十三、灰度发布与回滚流程

### 13.1 灰度发布前置

- [ ] staging 环境通过全部集成测试
- [ ] k6 压测达标（SLO 基线）
- [ ] 安全扫描 0 critical
- [ ] 备份已就绪（DB + MinIO + Vault）
- [ ] 监控看板就绪
- [ ] 应急联系人群就绪
- [ ] 发布窗口确认（默认周二/四 22:00）
- [ ] 业务方已通知

### 13.2 灰度发布步骤

```bash
# 1. 预发布检查（发布前 30 分钟）
./scripts/pre-deploy-check.sh --env=prod --tag=v3.1.0

# 2. 备份当前版本
argocd app history edam-prod > /backup/edam-prod-history-$(date +%Y%m%d).log
kubectl get all -n edam -o yaml > /backup/edam-prod-manifest-$(date +%Y%m%d).yaml

# 3. 数据库备份
mysqldump ... > /backup/edam-db-$(date +%Y%m%d).sql.gz

# 4. 触发金丝雀（10%）
kubectl argo rollouts set image edam-backend backend=v3.1.0

# 5. 观察 30 分钟
#    - 错误率
#    - P99 延迟
#    - 业务核心场景

# 6. 逐步放量（30% → 60% → 100%）
#    每步观察 30 分钟

# 7. 全量完成
kubectl argo rollouts get rollout edam-backend
# Status: Healthy, Image: v3.1.0
```

### 13.3 回滚决策

```bash
# 触发回滚
kubectl argo rollouts abort edam-backend
kubectl argo rollouts undo edam-backend

# 或 ArgoCD 回滚
argocd app rollback edam-prod --id <prev-revision>

# 验证回滚成功
kubectl get pods -n edam -l app.kubernetes.io/component=backend
# 应显示 v3.0.5 镜像

# 业务验证
curl https://api.example.com/health/ready
# {"status":"UP"}

# 通知
echo "EDAM prod 已从 v3.1.0 回滚到 v3.0.5" | \
  slack-notify --channel=edam-deployments
```

### 13.4 回滚后复盘

| 项 | 内容 | 时限 |
| --- | --- | --- |
| **5 Whys** | 5 个为什么追溯根因 | 24h |
| **Postmortem** | 复盘报告（含时间线 + 影响 + 改进） | 48h |
| **改进项** | 至少 3 项（短期/中期/长期）| 72h |
| **跟踪** | GitHub Issues + 月度评审 | 持续 |

---

## 十四、发布检查清单

### 14.1 发布前（发布前 1 天）

- [ ] 代码合入 main（CI 全绿）
- [ ] staging 验证通过（含 k6 压测）
- [ ] 安全扫描通过（Trivy 0 critical + GitLeaks 0）
- [ ] 备份已验证（DB + MinIO）
- [ ] 监控看板就绪
- [ ] 应急联系人群就绪
- [ ] 业务方已通知
- [ ] 变更窗口已确认（默认周二/四 22:00）
- [ ] Tag 已打（v*.*.*）
- [ ] Release Notes 已生成

### 14.2 发布中（实时）

- [ ] 多级审批已完成（Tech Lead + SRE + 安全 + PM）
- [ ] ArgoCD 手动 Sync 触发
- [ ] 滚动更新观察（Pod 状态）
- [ ] 健康检查通过（/health/live + /health/ready）
- [ ] HPA 正常扩容
- [ ] 数据库连接正常
- [ ] 缓存命中率正常
- [ ] 异步队列消费正常
- [ ] Vault 密钥注入正常
- [ ] Ingress TLS 证书有效
- [ ] 监控指标上报正常
- [ ] 业务核心场景验证通过
- [ ] 错误率 < 1%
- [ ] P99 延迟 < SLO

### 14.3 发布后（发布后 24h）

- [ ] 24h 内无 P0/P1 告警
- [ ] 24h 内无用户投诉
- [ ] 业务数据正常（无异常增长 / 流失）
- [ ] 资源使用率正常（CPU / 内存 / 磁盘）
- [ ] 备份自动运行成功
- [ ] CHANGELOG 已更新（[v3.x.y] 章节）
- [ ] 内部公告已发布（Slack / 钉钉）
- [ ] Postmortem（仅失败时）

---

## 十五、故障排查

### 15.1 Pod 启动失败

```bash
# 1. 查看 Pod 状态
kubectl get pods -n edam -l app.kubernetes.io/component=backend

# 2. 查看事件
kubectl describe pod -n edam <pod-name>

# 3. 查看日志
kubectl logs -n edam <pod-name> --previous

# 常见原因
# - ImagePullBackOff：镜像拉取失败
# - CrashLoopBackOff：应用启动失败
# - Pending：资源不足 / 镜像未拉取
```

### 15.2 数据库连接失败

```bash
# 1. 测试 MySQL 连通性
kubectl run -n edam mysql-test --rm -it --image=mysql:8.0 -- \
  mysql -h mysql.edam.svc.cluster.local -uedam -p"$DB_PASS" \
  -e "SELECT 1"

# 2. 查看后端日志
kubectl logs -n edam -l app.kubernetes.io/component=backend | grep HikariPool

# 3. 检查 Secret
kubectl get secret -n edam edam-db-secret -o yaml

# 4. 检查 NetworkPolicy
kubectl get networkpolicy -n edam
```

### 15.3 Vault 密钥同步失败

```bash
# 1. 查看 ExternalSecret 状态
kubectl describe externalsecret -n edam edam-db-credentials

# 2. 查看 SecretStore 状态
kubectl describe secretstore -n edam vault-backend

# 3. 查看 ESO 日志
kubectl logs -n external-secrets -l app=external-secrets

# 4. 测试 Vault 连接
kubectl run -n edam vault-test --rm -it --image=vault:latest -- \
  vault status -address=http://vault.vault.svc.cluster.local:8200
```

### 15.4 Ingress 不可达

```bash
# 1. 查看 Ingress 状态
kubectl describe ingress -n edam edam-prod

# 2. 查看证书
kubectl get certificate -n edam

# 3. 测试 Ingress Controller
kubectl get pods -n ingress-nginx

# 4. 测试 DNS
nslookup api.example.com

# 5. 测试 TLS
openssl s_client -connect api.example.com:443 -servername api.example.com
```

### 15.5 ArgoCD 不同步

```bash
# 1. 查看 Application 状态
argocd app get edam-prod

# 2. 强制刷新
argocd app refresh edam-prod

# 3. 强制同步
argocd app sync edam-prod --force

# 4. 查看 ArgoCD Controller 日志
kubectl logs -n argocd -l app.kubernetes.io/name=argocd-application-controller
```

### 15.6 常见问题速查表

| 症状 | 排查命令 | 可能原因 |
| --- | --- | --- |
| Pod Pending | `kubectl describe pod` | 资源不足 / 节点污点 |
| Pod CrashLoopBackOff | `kubectl logs --previous` | 应用启动失败 |
| HTTP 502 | `kubectl get endpoints` | Service 后端 Pod 未就绪 |
| HTTP 504 | Nginx 配置检查 | 上游超时 |
| Vault 401 | `kubectl logs ESO` | ServiceAccount 权限 |
| 镜像拉取失败 | `kubectl describe pod` | ImagePullSecret 缺失 |
| 磁盘满 | `df -h` | 日志未清理 |

---

## 十六、附录

### 16.1 关键路径速查

| 用途 | 命令 |
| --- | --- |
| **查看所有 Pod** | `kubectl get pods -n edam` |
| **查看后端日志** | `kubectl logs -n edam -l app.kubernetes.io/component=backend -f` |
| **查看 HPA** | `kubectl get hpa -n edam` |
| **查看 Ingress** | `kubectl get ingress -n edam` |
| **ArgoCD 应用列表** | `argocd app list` |
| **ArgoCD 同步** | `argocd app sync edam-prod` |
| **ArgoCD 回滚** | `argocd app rollback edam-prod` |
| **查看密钥** | `kubectl get secret -n edam` |
| **查看证书** | `kubectl get certificate -n edam` |
| **查看 ServiceMonitor** | `kubectl get servicemonitor -n edam` |
| **进入 Pod 调试** | `kubectl exec -it -n edam <pod-name> -- bash` |
| **端口转发** | `kubectl port-forward -n edam svc/edam-edam-backend 8080:8080` |
| **触发金丝雀** | `kubectl argo rollouts set image edam-backend backend=v3.1.0` |
| **金丝雀状态** | `kubectl argo rollouts get rollout edam-backend` |
| **Vault 状态** | `vault status` |
| **Vault 写入** | `vault kv put secret/edam/database password=...` |
| **MySQL 备份** | `mysqldump ... > backup.sql` |
| **MySQL 恢复** | `mysql ... < backup.sql` |

### 16.2 紧急联系方式

| 角色 | 联系方式 |
| --- | --- |
| **SRE 值班** | 钉钉群 + 电话 |
| **Tech Lead** | ________ |
| **安全负责人** | ________ |
| **CTO** | ________ |
| **云厂商支持** | 工单系统 + 7×24 电话 |

### 16.3 参考文档

| 文档 | 路径 |
| --- | --- |
| **方案书** | `doc/企业全格式数字资产防泄密系统技术方案书.docx` |
| **架构文档** | `ARCHITECTURE.md` |
| **Helm Chart** | `helm/edam/README.md` |
| **GitOps** | `gitops/README.md` |
| **本地开发** | `dev/README.md` |
| **应急 SOP** | `ops/sop/01-incident-response.md` + `01-incident-response-v2.md` |
| **等保 SOP** | `modify/2026-08-29-V4-01-等保测评申请执行方案.md` |
| **商密 SOP** | `modify/2026-08-29-V4-06-商密许可颁发执行方案.md` |
| **应急演练 SOP** | `modify/2026-08-29-V4-09-应急预案演练执行方案.md` |
| **v3.4 路线图** | `modify/2026-08-29-v3.4路线图.md` |

### 16.4 文档变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.0 | 2026-08-29 | 初版（覆盖 dev / staging / prod 三环境） |

---

## 与 CLAUDE.md 的对齐声明

本文档遵循 `CLAUDE.md` 的项目约定：

- ✅ 文档存放于 `modify/` 目录（不直接放在 `doc/`）
- ✅ Markdown 格式
- ✅ 与现有项目结构对齐（helm / gitops / dev / ops / monitoring）
- ✅ 与方案书 v3.1 §11「实施计划」 + §12「部署架构」对应
- ✅ 与 v3.4 路线图 V4-01（等保）+ V4-06（商密）+ V4-09（应急）合规要求对齐
- ✅ 所有技术细节基于现有项目文件（helm/edam/values.yaml、gitops/overlays/、argocd 配置）
- ✅ 自动 commit（遵循项目记忆 `git-auto-commit.md`）

---

**EDAM 部署文档 v1.0 完成。**

文档覆盖 dev / staging / prod 三套环境的完整部署链路，从本地开发 → ArgoCD 自动同步 → 多级审批 → 灰度发布 → 回滚预案全流程。等待团队按本文档推进部署。

如需更详细的某个章节、具体参数调优或案例演练，请指明具体方向。