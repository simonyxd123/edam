# EDAM 性能压测与容量规划（v3.3 W-15）

- 文档版本：v1.0
- 编制日期：2026-08-28
- 工具：k6（LoadImpact）

---

## 一、压测场景

### 1.1 基础场景

| 场景 | 描述 | 目标 |
| --- | --- | --- |
| **峰值负载** | 1000 并发 | 同时 1000 员工在线 |
| **稳态负载** | 500 并发 | 工作时间平均 |
| **峰值突发** | 2000 并发（短时）| 重大事件（如全员培训）|

### 1.2 操作分布

| 操作 | 占比 | 说明 |
| --- | --- | --- |
| 登录 | 10% | 用户首次访问 |
| 列出视频 | 30% | 视频浏览 |
| 视频详情 | 30% | 单视频浏览 |
| 播放 token | 20% | HLS 鉴权 |
| 列出文档 | 10% | 文档浏览 |

### 1.3 性能目标（SLO）

| 指标 | 目标 |
| --- | --- |
| P50 延迟 | < 100ms |
| P95 延迟 | < 200ms |
| P99 延迟 | < 500ms |
| 错误率 | < 1% |
| 吞吐 | ≥ 100 RPS（单实例） |

---

## 二、压测结果（待团队执行）

### 2.1 基础指标

实测报告由 `perf/k6/scripts/parse-k6.py` 自动生成到 `perf/k6/results/report-{TS}.html`：

```bash
# 一键执行 4 档位 + 生成报告
cd perf/k6 && ./scripts/run-all.sh

# 输出示例（report.html 包含）
# ┌─────────┬─────────┬────────┬───────┬───────┬───────┬────────┐
# │ stage   │ total   │ avgRPS │ P50   │ P95   │ P99   │ fail%  │
# ├─────────┼─────────┼────────┼───────┼───────┼───────┼────────┤
# │ smoke   │  xxx    │  xxx   │ xxx   │ xxx   │ xxx   │ xxx    │
# │ load    │  xxx    │  xxx   │ xxx   │ xxx   │ xxx   │ xxx    │
# │ peak    │  xxx    │  xxx   │ xxx   │ xxx   │ xxx   │ xxx    │
# │ stress  │  xxx    │  xxx   │ xxx   │ xxx   │ xxx   │ xxx    │
# └─────────┴─────────┴────────┴───────┴───────┴───────┴────────┘
```

### 2.2 性能瓶颈

| 层级 | 瓶颈点 | 优化方案 |
| --- | --- | --- |
| **前端** | 首屏加载慢 | CDN + 懒加载 |
| **网关** | Nginx 连接耗尽 | 调整 worker + keepalive |
| **后端** | MySQL 慢查询 | 索引 + 缓存（Redis）|
| **后端** | Vault 频繁调用 | 会话级缓存 |
| **Worker** | GPU 资源耗尽 | 任务优先级 + 限流 |

---

## 三、容量规划

### 3.1 单实例性能

| 指标 | 数值 |
| --- | --- |
| 后端 QPS（单 Pod） | 200-500 |
| Worker 视频处理 | 5-10 并发/实例 |
| MySQL TPS | 1000-3000 |
| Redis QPS | 10000+ |

### 3.2 集群规模

| 用户量 | 后端 Pod | MySQL | Redis | Worker |
| --- | --- | --- | --- | --- |
| 1,000 | 3 | 1 主 1 从 | 3 节点 | 2 |
| 5,000 | 5-8 | 1 主 2 从 | 3 节点 | 5 |
| 10,000 | 10-15 | 1 主 3 从 + Sharding | 5 节点 | 10 |
| 50,000 | 30-50 | 集群（分库分表）| 7 节点 | 30 |

### 3.3 存储容量

| 数据类型 | 单条大小 | 1 万员工/月 | 5 年 |
| --- | --- | --- | --- |
| 用户 | 1 KB | 10 MB | 50 MB |
| 视频（MinIO） | 500 MB | 500 GB（10% 员工上传）| 25 TB |
| 文档 | 5 MB | 50 GB | 2.5 TB |
| 操作日志 | 1 KB | 100 MB | 600 MB |
| 水印指纹 | 240 字节/视频 | 2.4 GB | 120 GB |

---

## 四、压测工具

### 4.1 k6（首选）

- 脚本化压测（JavaScript）
- 支持多阶段（爬升 / 稳态 / 峰值 / 降回）
- 实时输出 P50/P95/P99 延迟
- 阈值告警

### 4.2 其他工具

| 工具 | 用途 |
| --- | --- |
| **wrk** | HTTP 基准测试 |
| **Apache JMeter** | 复杂场景 |
| **Locust** | Python 分布式压测 |
| **Gatling** | JVM 生态 |

---

## 五、持续压测

### 5.1 CI 集成

v3.4 起切换到 `perf/k6/scripts/` 模块化套件，详见 `perf/k6/README.md` 第五节。

```yaml
# GitHub Actions（v3.4 V4-03 起）
name: Performance Test
on:
  workflow_dispatch:
  schedule:
    - cron: '0 2 * * 1'

jobs:
  k6-load:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: 启动 staging 环境
        run: ./scripts/start-staging.sh
      - name: 安装 k6
        run: sudo apt-get install -y k6
      - name: 跑负载测试
        run: k6 run --out json=perf/k6/results/load.json perf/k6/scripts/load-test.js
      - name: SLO 检查
        run: perf/k6/scripts/check-slo.sh load perf/k6/results/load.json
      - name: 上传报告
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: k6-report
          path: perf/k6/results/
```

> 注：v3.3 旧版 CI 配置（`grafana/k6-action` + `perf/load-test.js`）保留作为历史归档，新 CI 使用 `perf/k6/` 模块化套件。

### 5.2 自动告警

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| P99 > 500ms 持续 5min | 是 | P1 |
| 错误率 > 1% 持续 5min | 是 | P1 |
| 吞吐 < 100 RPS | 是 | P2 |
| CPU 使用率 > 80% 持续 10min | 是 | P2 |

---

## 六、容量扩展 SOP

### 6.1 垂直扩展（短期）

```yaml
# K8s Pod 资源
resources:
  requests:
    cpu: "1"
    memory: "2Gi"
  limits:
    cpu: "4"
    memory: "8Gi"
```

### 6.2 水平扩展（长期）

```yaml
# HPA
autoscaling:
  enabled: true
  minReplicas: 5
  maxReplicas: 50
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

### 6.3 数据库分库分表

- play_log 按月分表（已实施）
- user / video 按 user_id 哈希分库（万级后）

---

## 七、相关文档

- `helm/edam/values.yaml` — 资源配置
- `monitoring/prometheus.yml` — 指标采集
- `monitoring/grafana/` — 监控仪表板

## 八、v3.4 V4-03 实测报告位置

按 V4-03 路线图要求，v3.4 起的实测报告归档结构：

```
perf/k6/results/
├── smoke-{TS}.json              # 烟囱实测
├── load-{TS}.json               # 负载实测（SLO 验证）
├── peak-{TS}.json               # 峰值实测
├── stress-{TS}.json             # 极限实测
└── report-{TS}.html             # 汇总报告（含 SLO 检查）
```

**查看命令**：

```bash
# 最新一次报告
ls -lt perf/k6/results/report-*.html | head -1 | awk '{print $NF}' | xargs open

# 本周所有报告
ls -lt perf/k6/results/report-*.html | head -7
```

**报告流转**：

| 触发 | 报告路径 | 通知 |
| --- | --- | --- |
| 周一凌晨 2 点定时 | `perf/k6/results/report-{周一日期}.html` | Slack #perf-alerts |
| 发版前手动 | `perf/k6/results/report-{版本号}.html` | PR 评论 + Slack |
| 故障复盘 | `perf/k6/results/report-{事故日期}.html` | 故障报告附件 |

## 九、维护说明

- 本文档每季度 review 一次（与发版节奏对齐）
- SLO 阈值变更需经架构组 review
- 实测报告保留最近 12 份（30 天滚动），更早归档到 S3/MinIO
- 任何 k6 脚本变更需更新 `perf/k6/README.md` 与本文件第 5.1 节

---

**性能压测与容量规划指南完成。** 等待团队按周节奏执行 + 持续集成。