# MySQL 端口统一：容器内 3306 / 对外 3307

- 日期：2026-10-24
- 需求：MySQL 在 docker 里是 3306，对外是 3307，请统一

---

## 两层端口

| 场景 | 端口 | 说明 |
|---|---|---|
| **容器内** | 3306 | MySQL 默认，任何环境都是 |
| **宿主机 / 开发环境对外** | 3307 | `dev/docker-compose.yml` 的端口映射 `3307:3306` |

K8s（helm）里没有"宿主机映射"这回事 —— Service 端口和容器端口都是 3306。

## 改动前的不一致

| 位置 | 原值 | 问题 |
|---|---|---|
| `dev/docker-compose.yml` mysql ports | `3306:3306` | 宿主机 3306 若被占用，compose 直接起不来 |
| `application.yml` MYSQL_PORT | 3307 | ✓ 正确 |
| **`scripts/dev-reset.sh`** | **3306** | ✗ **连错库** |
| **`scripts/dev-diagnose.sh`** | **3306** | ✗ **连错库** |
| **`scripts/dev-check-admin.sh`** | **3306** | ✗ **连错库** |
| `helm/edam/values.yaml` MYSQL_PORT | 3306 | ✓ 正确（K8s Service 端口） |
| `.gitlab-ci.yml` | `mysql:3306` | ✓ 正确（service 同网络，用容器端口） |
| `.github/workflows/ci.yml` | `3306:3306` + `127.0.0.1:3306` | 内部一致，但与开发环境不同 |

**三个诊断脚本连的是 3306，而实际库在 3307** —— 也就是说
`dev-check-admin.sh` / `dev-diagnose.sh` / `dev-reset.sh`
**看的是另一个库**。这大概率就是"查数据库没有一个用户"却查不出所以然
的原因之一：脚本查的库和后端连的库根本不是同一个。

## 改动

1. `dev/docker-compose.yml`：`3306:3306` → **`3307:3306`**（+ 注释说明）
2. **三个脚本**：`MYSQL_PORT:-3306` → **`3307`**
   （`dev-reset.sh` 里还有一处**使用处**的硬编码兜底 `-P"${MYSQL_PORT:-3306}"`，一并去掉）
3. `.github/workflows/ci.yml`：service 映射 → `3307:3306`，flyway URL → `127.0.0.1:3307`
   （**顺带修了它指向已废弃的 `dev/db/migration`** —— 那个目录上轮被清空了，
   这个 workflow 的 db:migrate job 一直在读空目录）
4. 文档：`dev/db/README.md` / `README.md` 的 flyway 命令、
   `dev/README.md` 服务清单

**保持 3306 不动的**（它们是对的）：
- `helm/edam/values.yaml` MYSQL_PORT —— K8s Service 端口，容器内外同端口
- `.gitlab-ci.yml` 的 `mysql:3306` —— service 与 job 同网络，用容器端口
- `ops/sop/01-incident-response.md` 的 `http://mysql:3306` —— K8s 内部 Service

## 新增：MySQL 端口一致性校验

`dev/db/_validate_mysql_port.py` —— 把两层的所有来源列出来对照，
并区分 CI 的两种模式（GitLab 同网络用容器端口 / GitHub Actions 用映射端口）：

```
  3307:3306  compose mysql ports  [OK]
  3307  application.yml MYSQL_PORT  [OK]
  3307  scripts/dev-reset.sh  [OK]
  3307  scripts/dev-diagnose.sh  [OK]
  3307  scripts/dev-check-admin.sh  [OK]
  3306  helm MYSQL_PORT（K8s Service，容器内外同端口）  [OK]
  [('mysql', '3306')]  .gitlab-ci.yml service 端口  [OK]
  [('127.0.0.1', '3307'), ('127.0.0.1', '3307')]  github ci.yml service 端口  [OK]
  3307:3306  github ci.yml service 映射  [OK]

✅ 全部一致：容器内 3306 / 对外 3307
```

已接进 CI 的 `db:migrate` job。

（写这个校验器时自己也踩了两个坑并修掉了：正则先命中了 redis 的
`6379:6379`；CI 里用 `mysql:(\d+)` 匹配到了**镜像 tag** `mysql:8.0`。）

## 验证

```
后端 compile              OK
后端测试 (30)             OK
DB 6 校验                 OK   ← 新增 MySQL 端口校验
OpenAPI 覆盖率            OK (100%)
前端 lint + vue-tsc + 19   OK
e2e (19)                  OK
helm × 3 overlay          OK
compose + CI YAML         OK
4 个脚本语法              OK
```

## 服务器侧注意

MySQL 对外端口变成 3307 后：
- `docker compose down -v && docker compose up -d` 会用新映射重建
- 宿主机上若已有别的 MySQL 占用 3306，现在不再冲突（这本来就是改动的目的）
- 后端 `application.yml` 的 MYSQL_PORT 默认 3307，**不需要额外配置**
