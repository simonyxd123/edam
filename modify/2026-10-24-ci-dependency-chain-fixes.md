# CI 依赖链最终核对：修 e2e job 的两个必挂问题

- 日期：2026-10-24
- 范围：.gitlab-ci.yml

---

## 背景

CI 有 10 个 job，此前**从未在真实 CI 上跑绿过**（.gitlab-ci.yml 原本
连合法 YAML 都不是）。本轮逐个 job 核对"运行前置是否齐备"，
发现 e2e:test 有两个**必然失败**的问题。

## 一、e2e:test 的 playwright 版本与镜像不匹配 → 必挂

| | 值 |
|---|---|
| `e2e/package-lock.json` 锁定 | playwright **1.62.1** |
| CI 镜像 | `mcr.microsoft.com/playwright:v1.45.0-jammy` → 自带 **1.45.0** 的浏览器 |
| `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` | `"1"` → npm ci **不**下载浏览器 |

三者凑在一起：playwright 1.62.1 去找浏览器，但 `/ms-playwright` 里
只有 1.45.0 的二进制，而下载又被禁用了 → **启动即失败**。

修复：
- 镜像 tag 改成 `v1.62.1-jammy`（与 lock 一致）
- 显式加 `PLAYWRIGHT_BROWSERS_PATH: /ms-playwright`
  （playwright 1.40+ 用复数形式；不设的话会去 `~/.cache/ms-playwright` 找）

## 二、e2e:test 的双 `cd e2e` → 必挂

GitLab Runner 把 `before_script` 和 `script` 拼进**同一个 shell**：

```
before_script:  cd e2e && npm ci        → cwd 已经是 e2e/
script:         cd e2e && npx playwright   → cd e2e/e2e → 不存在 → 失败
```

（`worker:test` 之前踩过**完全相同**的坑，已修；e2e 这个是漏网的。）

修复：script 里不再 `cd e2e`。

## 三、db:migrate 的固定 sleep 30

`sleep 30` 等 MySQL 起来：慢机器上不够（会随机失败），快机器上白等。
改成循环探测（与 backend:test 同样的写法）：

```bash
for i in $(seq 1 30); do
  if mysql -hmysql -uroot -ptest -e "SELECT 1" >/dev/null 2>&1; then
    echo "MySQL ready"; break
  fi
  sleep 2
done
```

## 四、逐 job 核对结果

| job | image | services | 结论 |
|---|---|---|---|
| docs:lint | python:3.10 | - | OK（helm/bc/python 都装了） |
| db:migrate | mysql:8.0 | mysql | OK（修了 sleep） |
| backend:test | maven:3.9 | mysql, rabbitmq | OK |
| worker:test | python:3.10 | - | OK（pytest.ini 配了 pythonpath=src） |
| e2e:test | playwright:v1.62.1-jammy | - | OK（修了版本 + 双 cd） |
| frontend:build | node:18 | - | OK（package-lock.json 已补齐） |
| docker:build | docker:24 | dind | OK |
| helm:package | alpine:3.18 | - | OK（before_script 装了 helm） |
| deploy:staging / production | alpine:3.18 | - | OK（带 -f overlays，覆盖 worker/web tag） |

## 验证

```
后端 compile              OK
后端测试 (25)             OK
worker 测试 (11)          OK
DB 3 校验                 OK
OpenAPI 覆盖率            OK (100%)
前端 lint + vue-tsc + 19  OK
e2e (19)                  OK
helm × 3 overlay          OK
CI YAML                   OK
playwright lock 版本 == 镜像 tag   OK
```
