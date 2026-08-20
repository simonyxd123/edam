# EDAM k6 压测套件（v3.4 V4-03）

- **适用版本**：v3.4 起的所有主版本
- **执行周期**：每个 Sprint / 每个发版前
- **关联文档**：`perf/capacity-planning.md`、`modify/2026-08-29-V4-03-k6压测执行计划.md`
- **配套规范**：`modify/2026-08-28-项目最终总结v1.0-v3.3.md` 第六节「性能指标（SLO）」

---

## 一、目录结构

```
perf/k6/
├── README.md                       # 本文件
├── lib/
│   ├── config.js                   # 全局配置 + 测试用户池 + 流量配比
│   ├── metrics.js                  # 自定义指标（errorRate / 各 Trend）
│   └── helpers.js                  # 通用 helpers（authHeaders / getRandomId / thinkTime）
├── scenarios/                      # 8 类端点场景（每类一个文件）
│   ├── auth.js                     # 登录 / 刷新 / 当前用户 / 登出
│   ├── videos.js                   # 列表 / 详情 / 批量
│   ├── documents.js                # 列表 / 详情 / 下载
│   ├── playback.js                 # 播放 Token / 密钥 / 日志上报
│   ├── watermarks.js               # 水印提取 / 缓存
│   ├── distribution.js             # 创建 / 审批 / 列表
│   ├── search.js                   # 视频 / 文档全文搜索
│   ├── notifications.js            # 通知列表 / 标记已读
│   └── audit.js                    # 审计日志查询 / 导出
├── scripts/                        # 4 档位入口 + 辅助工具
│   ├── smoke.js                    # 烟囱：10 VUs × 5min
│   ├── load-test.js                # 负载：200 QPS × 10min
│   ├── peak-test.js                # 峰值：500 QPS × 10min
│   ├── stress-test.js              # 极限：1000 QPS × 5min
│   ├── run-all.sh                  # 一键跑 4 档位 + 生成 HTML 报告
│   ├── parse-k6.py                 # JSON → HTML 报告
│   └── check-slo.sh                # 单档 SLO 检查（CI 用）
└── results/                        # 输出（gitignore）
    ├── smoke-{TS}.json
    ├── load-{TS}.json
    ├── peak-{TS}.json
    ├── stress-{TS}.json
    └── report-{TS}.html
```

---

## 二、快速开始

### 2.1 安装 k6

```bash
# macOS
brew install k6

# Linux (Debian/Ubuntu)
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Windows
choco install k6

# Docker（推荐 CI 使用）
docker pull grafana/k6:latest
```

### 2.2 准备测试用户

k6 脚本默认使用 `TEST_001 ~ TEST_NNN` 用户池，需提前 seed：

```bash
# 在 dev 环境 seed
mysql -uroot -p edam < dev/db/seed/seed_k6_users.sql
# seed 100 个测试用户（密码统一为 TestP@ssw0rd!）
```

### 2.3 跑单档位

```bash
cd perf/k6

# 烟囱（验证链路）
k6 run --out json=results/smoke.json scripts/smoke.js

# 负载（验证 SLO）
k6 run --out json=results/load.json scripts/load-test.js

# 峰值（验证弹性）
k6 run --out json=results/peak.json scripts/peak-test.js

# 极限（找瓶颈）
k6 run --out json=results/stress.json scripts/stress-test.js
```

### 2.4 一键跑全部 4 档位

```bash
cd perf/k6
./scripts/run-all.sh
# 默认压测 localhost:8080

# 自定义环境
BASE_URL=http://staging.example.com/api/v1 ENV_NAME=staging ./scripts/run-all.sh
```

### 2.5 SLO 检查（CI 集成）

```bash
# 单档检查
./scripts/check-slo.sh load results/load.json
# 失败 → exit 1
```

---

## 三、SLO 阈值

来源：`modify/2026-08-28-项目最终总结v1.0-v3.3.md` 第六节「性能指标（SLO）」

| 档位 | QPS / VUs | P50 | P95 | P99 | 失败率 | 适用场景 |
| --- | --- | --- | --- | --- | --- | --- |
| **smoke** | 10 VUs × 5min | ≤ 200ms | ≤ 400ms | ≤ 800ms | ≤ 2% | 验证脚本链路 |
| **load** | 200 QPS × 10min | **≤ 100ms** | **≤ 200ms** | **≤ 500ms** | **≤ 1%** | 验证 SLO 达标 |
| **peak** | 500 QPS × 10min | ≤ 150ms | ≤ 300ms | ≤ 800ms | ≤ 2% | 验证弹性 + 告警 |
| **stress** | 1000 QPS × 5min | — | — | — | — | 找瓶颈（不设阈值） |

> **核心 SLO（生产环境）**：P50 < 100ms / P95 < 200ms / P99 < 500ms / 错误率 < 1% / 单 Pod QPS ≥ 200

---

## 四、流量配比

按业务实际分布（来自 v3.3 W-15 实测 + V4-03 路线图）：

| 类别 | 端点示例 | 占比 |
| --- | --- | --- |
| 鉴权（auth） | /auth/login, /auth/refresh, /users/me | 10% |
| 视频浏览（video_browse） | /videos, /videos/{id}, /videos/batch | 25% |
| 视频播放（video_play） | /playback/{id}/token, /playback/log | 20% |
| 文档浏览（document_browse） | /documents, /documents/{id} | 10% |
| 文档下载（document_download） | /documents/{id}/download | 5% |
| 水印（watermark） | /watermarks/extract, /watermarks/cache | 10% |
| 外发审批（distribution） | /distribution, /distribution/{id}/approve | 5% |
| 搜索（search） | /search/videos, /search/documents | 10% |
| 通知（notifications） | /notifications | 3% |
| 审计（audit） | /audit/logs, /audit/logs/export | 2% |

> 注：load-test.js / peak-test.js / stress-test.js 中的具体比例与本表略有差异（按各档优化），但总量分布保持总体匹配。

---

## 五、CI 集成（GitHub Actions 示例）

```yaml
# .github/workflows/perf.yml
name: Performance Test
on:
  workflow_dispatch:        # 手动触发
  schedule:
    - cron: '0 2 * * 1'     # 每周一凌晨 2 点

jobs:
  k6-load:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: 启动 staging 环境
        run: |
          echo "启动依赖 + 后端"
          docker compose -f dev/docker-compose.yml up -d
          ./scripts/start-backend.sh

      - name: 安装 k6
        run: |
          sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
          echo "deb https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
          sudo apt-get update && sudo apt-get install k6

      - name: 跑负载测试
        run: |
          cd perf/k6
          k6 run --out json=results/load.json scripts/load-test.js

      - name: SLO 检查
        run: |
          cd perf/k6
          ./scripts/check-slo.sh load results/load.json

      - name: 上传报告
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: k6-report
          path: perf/k6/results/
```

---

## 六、与旧脚本的兼容

旧 `perf/load-test.js`（v3.3 W-15 单文件脚本）保留作为历史归档，新脚本使用模块化结构：

| 维度 | 旧 `perf/load-test.js` | 新 `perf/k6/scripts/load-test.js` |
| --- | --- | --- |
| 端点覆盖 | 5 类（auth/videos/playback/documents/health） | 10 类（8 大类 + 9 个细分操作）|
| 档位 | 单档（1000 VUs）| 4 档（smoke/load/peak/stress）|
| 模块化 | 单文件 | lib + scenarios + scripts 三层 |
| 报告 | 仅 stdout | JSON + HTML（含 SLO 检查）|
| CI | 无 | check-slo.sh 集成 |

迁移建议：

1. CI 工作流从 `perf/load-test.js` 切换到 `perf/k6/scripts/load-test.js`
2. 删除/归档旧 `perf/load-test.js`（v3.5 时机，由 V4-13 ISO 27001 复审驱动）

---

## 七、问题排查

### 7.1 login() 失败率高

- 检查 `BASE_URL` 是否正确
- 检查测试用户池是否已 seed（`TEST_001 ~ TEST_NNN`）
- 检查后端 `/auth/login` 是否限流（Bucket4j 默认 3/min/工号）

### 7.2 P99 飙升但 P50/P95 正常

- 检查是否有慢查询（MySQL slow log）
- 检查 Vault 是否频繁调用（建议会话级缓存）
- 检查 Redis 连接池是否打满

### 7.3 水印相关接口超时

- 检查 Worker 队列是否堆积（RabbitMQ management）
- 检查 GPU 资源（`kubectl top pod -l app=worker`）
- 临时降级：调整 `load-test.js` 中 watermark 比例从 10% → 2%

### 7.4 结果 HTML 报告乱码

- 确保 `parse-k6.py` 用 UTF-8 编码（已 hardcode）
- 浏览器切换到 UTF-8 编码

---

**k6 压测套件就绪。** 与 V4-03 路线图一致，等待团队按周节奏执行 + 持续集成。