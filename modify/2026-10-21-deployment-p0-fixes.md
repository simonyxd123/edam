# 部署配置 P0 修复（第三轮）

- 日期：2026-10-21
- 范围：helm/、gitops/、.gitlab-ci.yml、README.md、dev/monitoring/
- 承接：`modify/2026-10-20-full-project-review-and-p0-fixes.md`、
  `modify/2026-10-20-frontend-p0-and-toolchain.md`

这一轮的核心结论：**`.gitlab-ci.yml` 原本就不是合法 YAML**，
`helm chart` 按现状部署也起不来。都是"看起来配了、实际不生效"。

---

## 一、`.gitlab-ci.yml` 原本无法解析

```yaml
docs:lint:
  script:
    - |
      ...
    artifacts:          # ← 4 空格缩进，应为 2（与 script: 同级）
      paths:
        - modify/controller-coverage.md
      expire_in: 30 days  # ← 6 空格缩进，应为 4（与 paths: 同级）
```

YAML 报错 `expected <block end>, but found '?'`。
**11 个 job 一个都跑不起来** —— 也就是说这个仓库的 CI 从来没有绿过。

修复：两处缩进。

验证：`yaml.safe_load()` 现在能解析出 11 个 job。

---

## 二、Helm chart：按现状部署必然起不来

| 问题 | 后果 | 修复 |
|---|---|---|
| 探针路径 `/health/live` | 后端 `context-path=/api/v1`，真实路径是 `/api/v1/health/live` → **404** → readiness 失败 Pod 永不进 Endpoints、liveness 失败 60s 后 CrashLoopBackOff | 三个 path 全部补 `/api/v1` 前缀 |
| `templates/` 下**没有** `serviceaccount.yaml` | 两个 Deployment 都写 `serviceAccountName: edam` → Pod 起来卡 `CreateContainerConfigError: serviceaccount "edam" not found` | 新增 `templates/serviceaccount.yaml`（`automountServiceAccountToken: false`） |
| Secret 生成了 `jwt-secret` 但**没有任何 Pod 引用** | 生产 JWT 全用 application.yml 里的硬编码 dev 密钥签名 —— 任何人知道那个字符串就能伪造任意用户 token | deployment 注入 `JWT_SECRET` |
| 注入的环境变量叫 `DB_PASSWORD`，代码读 `MYSQL_PASSWORD` | 精心在 Vault 里设的密码**根本没被用上**，实际连库用的是默认值 `edampass` | 改名 `MYSQL_PASSWORD` |
| Worker 回调端点没注入共享密钥 | 后端已改为要求 `X-Worker-Token`（未配置则 fail-closed 拒绝） | 注入 `WORKER_TOKEN` |
| worker-deployment **无 ports / 无探针 / 无关键 env** | `BACKEND_BASE_URL` 落到默认值 `http://localhost:8092` → worker 回调后端全部打到自己的端口，必失败且无日志可见；MinIO 落到 `localhost:9000` → 根本不在那个网络 namespace；崩了的 worker 一直占副本 | 补 ports + `/health/live` `/health/ready` 探针 + `BACKEND_BASE_URL` / `MINIO_*` / `RABBITMQ_*` / `OPS_ENABLED` 等环境变量 |
| Worker 的 `/health/worker` 探测后端 URL 缺 context-path | 探测永远 404 → `overall_ok` 恒 false | 候选 URL 列表（带/不带 `/api/v1` 都试） |

另外 `secret.yaml` 补 `worker-token` / `admin-password` 键，
`values.yaml` 补对应项 + `worker.service.port`。

**验证**：`helm lint`（带 prod overlay）0 failed；
`helm template` 对 dev/staging/prod 三个 overlay 全部渲染成功，
探针路径、`ServiceAccount`、四个 secret 注入均已确认出现在产物里。

---

## 三、ArgoCD：overlay 100% 不生效

### 3.1 `valueFiles` 越出 source root

三个静态 Application 都写：

```yaml
path: helm/edam
valueFiles:
  - ../../gitops/overlays/dev/values.yaml   # ← 越界
```

ArgoCD 的 `valueFiles` **只能在 source root 内解析**，`..` 越界会被直接拒绝
（`app path ... is not within the root`），不是"静默用旧值"，是**同步直接失败**。

修复：source root 改成仓库根 + `chart:` 字段指到子目录：

```yaml
path: .
chart: helm/edam
helm:
  valueFiles:
    - gitops/overlays/<env>/values.yaml
```

### 3.2 ApplicationSet 与静态 Application 名字冲突 → 删除 ApplicationSet

原 `applicationset.yaml` 用 git **directories** generator，有 5 个叠加问题：

1. 和 `applications/` 下 3 个静态 Application **名字冲突**（都叫 `edam-dev` 等）
2. directories generator 会**跳过**不含 `Chart.yaml` / `kustomization.yaml` 的目录
   —— `gitops/overlays/dev/` 里只有 `values.yaml` → **生成 0 个 Application**
3. 模板上下文里没有 `targetRevision` 字段（`{{.git.targetRevision}}` 渲染报错）
4. 没开 `goTemplate: true`，老式 fasttemplate 不求值 `{{path.basename}}`
   → releaseName 变成字面量 `edam-{{path.basename}}`（非法 K8s 名字）
5. `valueFiles` 同样越界

**决策：删除 ApplicationSet，保留 3 个静态 Application。**
理由：环境就 3 个，且本来就需要 per-env 差异化同步策略
（dev 自动 / staging 手工 / prod 手工 + 多级审批），
静态 Application 显式写死更可控，也不存在模板求值问题。
删除理由已写进 `gitops/README.md`。

### 3.3 AppProject YAML 缩进错误

`roles:` 下第二个条目 `- name: developer` 缩进比第一个少 2 空格
→ 整个文件解析失败。已修。

### 3.4 External Secrets 产出的 Secret 名字没人用

ESO 产出 `edam-db-secret` / `edam-jwt-secret` / `edam-vault-token`，
chart 消费的是 `edam-secret`（`db-password` / `vault-token` / `jwt-secret` 键）。
**名字不同 → ESO 的同步结果没有任何 Pod 在用**，Vault 链路是断的。

修复：ESO 的 `target.name` 统一改成 `edam-secret`。

---

## 四、CI 流水线里的失效门禁

| 问题 | 后果 | 修复 |
|---|---|---|
| `bc` 没装（python:3.10 镜像里没有） | `bc -l` 报 command not found → 命令替换得到空串 → `[ "" = "1" ]` 恒假 → **覆盖率掉到 0% 也照样绿灯**，95% 门禁形同虚设 | `apt-get install ... curl bc` |
| `helm upgrade` 不带 `-f gitops/overlays/<env>/values.yaml` | overlay 里的副本数 / 资源限额 / 阈值全部不生效；prod 也在用 base values 的 3 副本 + 8g ingress | 两个 deploy job 都加 `-f` |
| 只 `--set backend.image.tag` | worker 留在 `values.yaml` 里的 `3.1.0`，而 **CI 只推 SHA tag，那个 tag 在 registry 里根本不存在** —— 靠 `imagePullPolicy: IfNotPresent` 命中节点缓存侥幸"能跑"，实际新旧混版 | 同时覆盖 `worker.image.tag` / `web.image.tag` |
| `worker:test` 的 `before_script` 和 `script` **都** `cd worker` | GitLab 把两者拼进同一个 shell → 第二句 `cd worker` 报 "No such file or directory" → job 必失败 | `before_script` 不 cd，`script` 用 `cd worker && pytest` |
| flyway 连续两次 `migrate`（migration / seed 两个 locations） | 两次调用共用默认 `flyway_schema_history` 表，第二次报 `Detected applied migration not resolved locally` | 合成一次，`-locations=filesystem:dev/db/migration,filesystem:dev/db/seed` |
| `helm lint helm/edam` 不带 overlay | lint 的是 base values，不是实际部署的配置 | 加 `-f gitops/overlays/prod/values.yaml` |

---

## 五、文档与监控

| 位置 | 问题 | 修复 |
|---|---|---|
| `README.md` | 前端写 `http://localhost:3000`（**和 Grafana 撞端口**，实际是 5173）；后端写 8080（dev 实际走 8092）；worker 启动命令缺 `PYTHONPATH=src` → `ModuleNotFoundError` | 全部改正并注明端口来源 |
| `dev/monitoring/prometheus.yml` | `metrics_path: '/actuator/prometheus'` 缺 `/api/v1` 前缀 → **一个 sample 都收不到** → `BackendHighErrorRate` 等整条 backend 告警链永远不触发 | 补前缀 |

---

## 验证

```
yaml.safe_load(.gitlab-ci.yml)        → 11 jobs 解析成功
helm lint helm/edam -f overlays/prod  → 0 chart(s) failed
helm template × 3 overlays            → 全部渲染成功
yaml.safe_load_all(gitops/**/*.yaml)  → 8 个文件全部合法
```

## 待办（未在本次范围）

- `helm/edam/values.yaml` 里定义了完整的 `nginx.*`（副本数、4Gi 限额、
  ConfigMap），但 **templates 里没有 nginx 模板，也没有 web 前端模板**；
  ingress 只有一个 `path: /` 打到 backend → **线上按这套 chart 部署出来
  访问根路径得到后端 404，前端根本不存在**。
  这需要新增 nginx + web 两个模板并重排 ingress 路由，属独立迭代。
- `readOnlyRootFilesystem: true` 与日志落盘 `/var/log/edam/backend.log` 冲突
  （该目录不存在也没挂 volume）→ Logback FileAppender 失败，文件日志丢失
- 8GB 上传依赖的 `/tmp` emptyDir 没有 `sizeLimit`，也没有
  `ephemeral-storage` requests/limits → 调度器看不见这块压力
- 两套互不兼容的 DB 迁移脚本（backend classpath 的 V1-V3 vs
  dev/db 的 V2026*）
- e2e 测试完全没接进 CI
- WebSocket `/ws` 重复注册 + 完全无鉴权
- 限流器内存无界 + `X-Forwarded-For` 可伪造
- SSO 路径匹配漏 context-path、PKCE/state 形同虚设
- 7 份重复的 `toPaginationResponse` / 3 份 `statusToString` 应收敛
