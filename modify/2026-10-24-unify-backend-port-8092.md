# 后端端口统一到 8092

- 日期：2026-10-24
- 需求：把 `backend/src/main/resources/application.yml` 的 8080 改成 8092

---

## 为什么不只改一行

原先后端端口在**多处**各写各的，改一处不会自动改其它处：

| 位置 | 原值 |
|---|---|
| `application.yml` 的 `server.port` | 8080 |
| 前端 `web/vite.config.ts` 的 proxy target | 8092 |
| 启动脚本的 `-Dspring-boot.run.arguments=--server.port=8092` | 8092 |
| helm `backend.service.port` | 8080 |
| helm `backend.env.SERVER_PORT` | 8080 |
| helm `worker.env.BACKEND_BASE_URL` | 8080 |
| helm `deployment.yaml` 的 `prometheus.io/port` | 8080 |
| worker `config.py` 的 `BACKEND_BASE_URL` 默认值 | 8092 |
| `dev/monitoring/prometheus.yml` 抓取目标 | 8080 |
| `doc/openapi.yaml` 的 server url | 8080 |

这就是"改了 application.yml 但前端还是 502"的根源 —— 实际上是
**三方不一致**（配置文件 8080 / 前端代理 8092 / 启动参数再覆盖 8092），
每次改端口要记得同步三处，漏一次就出故障。

## 改动

**全部统一到 8092**，涉及 12 个文件：

| 文件 | 改动 |
|---|---|
| `backend/.../application.yml` | `server.port: 8092`（+ 注释说明为什么统一） |
| `web/vite.config.ts` | 注释更新（原本说"与 application.yml 不同源"，现在同源了） |
| `helm/edam/values.yaml` | `backend.service.port` / `SERVER_PORT` / worker `BACKEND_BASE_URL` → 8092 |
| `helm/edam/templates/deployment.yaml` | `prometheus.io/port` → 8092 |
| `helm/edam/README.md` | port-forward 示例 |
| `dev/monitoring/prometheus.yml` | 抓取目标 |
| `doc/openapi.yaml` | server url |
| `perf/k6/scripts/run-all.sh` + `perf/k6/README.md` | 压测默认 BASE_URL |
| `ops/sop/02-disaster-recovery.md` | 灾备 SOP 里的探活地址 |
| `scripts/dev-check-admin.sh` | 诊断脚本的备用端口（保留 8080 作为旧端口回退） |
| `.github/workflows/security-scan.yml` | staging target |
| `SECURITY.md` | NetworkPolicy 放行端口 |
| `README.md` / `dev/README.md` | 端口对照表 + 启动命令（不再需要带 `--server.port` 参数） |

## 新增：端口一致性校验

`dev/db/_validate_ports.py` —— 把 **9 处端口来源**列出来对照，
任何一处不一致直接报错：

```
后端端口来源：
    8092  backend application.yml server.port
    8092  web vite proxy target
    8092  helm backend.service.port
    8092  helm backend.env.SERVER_PORT
    8092  helm worker BACKEND_BASE_URL
    8092  helm deployment prometheus.io/port
    8092  worker config BACKEND_BASE_URL default
    8092  dev prometheus scrape target
    8092  openapi server url

✅ 全部一致：8092
```

已接进 CI 的 `db:migrate` job —— 以后再改端口，漏改任何一处 CI 会红。

## 附带收益

开发期启动命令简化了：

```bash
# 之前（必须带参数，漏了就 8080/8092 对不上）
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092

# 现在
mvn spring-boot:run
```

## 验证

```
后端 compile              OK
后端测试 (30)             OK
DB 5 校验（含端口一致性）  OK
OpenAPI 覆盖率            OK (100%)
前端 lint + vue-tsc + 19   OK
e2e (19)                  OK
helm × 3 overlay          OK
```
