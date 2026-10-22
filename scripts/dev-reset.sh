#!/usr/bin/env bash
# ===================================================================
# EDAM 开发环境一键重置并启动
# ===================================================================
# 解决"启动报 Flyway 错误"的完整流程。三个独立的污染源都要清：
#
#   1. backend/target/classes/db/migration/ 里有**已删除的旧迁移**
#      （Maven 的 resources 插件只复制/覆盖，不删除源里已移除的资源）
#      → 解决：mvn clean
#
#   2. MySQL 数据卷里的表是 **docker-entrypoint-initdb.d 裸执行 SQL** 建的
#      （不写 flyway_schema_history）→ Flyway 看到"空 history"会从 V1 重跑
#      → 解决：删数据卷重建（现在 initdb.d 已不挂载，schema 全归 Flyway 管）
#
#   3. flyway_schema_history 里有 success=0 的失败记录
#      → 解决：删数据卷（2 已覆盖）
#
# 用法：
#   ./scripts/dev-reset.sh              # 重置 DB + clean + 启动后端
#   ./scripts/dev-reset.sh --no-db      # 只 clean + 启动（保留现有数据）
#   ./scripts/dev-reset.sh --no-start   # 只重置，不启动
# ===================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RESET_DB=1
START=1
for arg in "$@"; do
  case "$arg" in
    --no-db)    RESET_DB=0 ;;
    --no-start) START=0 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

# ---- 0. 拉最新代码 ----------------------------------------------------
if command -v git >/dev/null 2>&1 && [ -d "$REPO_ROOT/.git" ]; then
  echo "==> git pull"
  git pull --ff-only || echo "    (拉取失败，继续用本地代码)"
fi

# ---- 1. 清理后端编译产物（陈旧迁移的来源）-----------------------------
echo "==> 清理 backend/target"
rm -rf "$REPO_ROOT/backend/target"

# ---- 2. 重建 MySQL（清掉 initdb.d 建的旧表 + 失败的 history）-----------
if [ "$RESET_DB" = "1" ]; then
  if command -v docker >/dev/null 2>&1; then
    echo "==> 重建 MySQL 数据卷（会清空 edam 库）"
    ( cd "$REPO_ROOT/dev" && docker compose down -v && docker compose up -d )
    echo "    等待 MySQL 就绪…"
    for i in $(seq 1 60); do
      if ( cd "$REPO_ROOT/dev" && docker compose exec -T mysql \
            mysqladmin ping -h 127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD:-rootpass}" \
            >/dev/null 2>&1 ); then
        echo "    MySQL 就绪"
        break
      fi
      sleep 2
    done
  else
    echo "    未找到 docker，跳过数据库重建"
  fi
fi

# ---- 3. 编译 + 启动 ----------------------------------------------------
if [ "$START" = "1" ]; then
  echo "==> 启动后端（开发端口 8092）"
  cd "$REPO_ROOT/backend"
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"
  nohup /usr/bin/mvn spring-boot:run \
    -Dspring-boot.run.arguments=--server.port=8092 \
    > "$REPO_ROOT/backend/backend.log" 2>&1 &
  echo "    PID=$!  日志: backend/backend.log"
  echo
  echo "完成后可访问："
  echo "  前端  http://192.168.10.2:5173/   （另开终端 cd web && npm run dev）"
  echo "  后端  http://192.168.10.2:8092/api/v1"
  echo "  账号  工号 E000001 / 密码 admin123（dev profile）"
else
  echo "==> 完成（未启动后端）"
  echo "    启动命令：cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092"
fi
