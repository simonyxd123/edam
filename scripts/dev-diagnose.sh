#!/usr/bin/env bash
# ===================================================================
# EDAM 启动失败诊断（一次性输出全部关键状态）
# ===================================================================
# 用法（在服务器上）：
#     cd /var/data/project/edam && ./scripts/dev-diagnose.sh
#
# 输出会直接回答这几个问题：
#   1. 服务器上的代码是不是最新的？（git HEAD vs origin）
#   2. backend/target/classes/db/migration/ 里有没有**已删除的旧迁移**？
#      （Maven 的 resources 插件只复制/覆盖，不删除源里已移除的资源）
#   3. 库里有哪些表？flyway_schema_history 是什么状态？
#   4. FK 相关列的整数字是 signed 还是 unsigned？
#   5. 结论 + 下一步该做什么
# ===================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SEP="============================================================"
say() { echo; echo "$SEP"; echo "$*"; echo "$SEP"; }

# ---- MySQL 连接参数（与 dev/docker-compose.yml 一致）------------------
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-rootpass}"
MYSQL_DB="${MYSQL_DATABASE:-edam}"

mysql_q() {
    if command -v mysql >/dev/null 2>&1; then
        mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" -N -B "$MYSQL_DB" -e "$1" 2>/dev/null
    elif command -v docker >/dev/null 2>&1; then
        docker exec -i edam-mysql mysql -uroot -p"$MYSQL_PASS" -N -B "$MYSQL_DB" -e "$1" 2>/dev/null
    fi
}

say "1. 代码版本"
git log -1 --pretty='  本地 HEAD : %h %s (%ci)' 2>/dev/null
git fetch -q origin 2>/dev/null && \
  echo "  origin/main: $(git rev-parse --short origin/main 2>/dev/null) $(git log -1 --pretty=%s origin/main 2>/dev/null)" || \
  echo "  (无法连接 origin)"
BEHIND=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo "?")
echo "  落后 origin/main : $BEHIND 个提交"
if [ "$BEHIND" != "0" ] && [ "$BEHIND" != "?" ]; then
  echo "  >>> 结论：服务器代码不是最新，**必须先 git pull**"
fi

say "2. 编译产物里的迁移脚本（陈旧产物检测）"
if [ -d backend/target/classes/db/migration ]; then
  ls -1 backend/target/classes/db/migration/*.sql 2>/dev/null | while read -r f; do
    printf '  %-55s %s\n' "$(basename "$f")" "$(date -r "$f" '+%Y-%m-%d %H:%M')"
  done
  STALE=$(ls -1 backend/target/classes/db/migration/ 2>/dev/null \
    | grep -E '^(V1__|V2__|V3__)' || true)
  if [ -n "$STALE" ]; then
    echo
    echo "  >>> 发现已废弃的迁移残留：$STALE"
    echo "  >>> 这是 Error 3780 的直接原因。解决：rm -rf backend/target"
  else
    echo
    echo "  >>> 无陈旧残留 ✅"
  fi
else
  echo "  (target 尚未编译)"
fi

say "3. 源码里的迁移（权威版本）"
ls -1 backend/src/main/resources/db/migration/*.sql 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/^/  /'

say "4. 数据库现状"
TABLES=$(mysql_q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$MYSQL_DB';")
if [ -z "$TABLES" ]; then
  echo "  (连不上数据库：$MYSQL_HOST:$MYSQL_PORT 用户 $MYSQL_USER)"
else
  echo "  表数量: $TABLES"
  echo "  --- flyway_schema_history ---"
  HISTORY=$(mysql_q "SELECT IFNULL(GROUP_CONCAT(CONCAT(version,':',description,'/ok=',success) SEPARATOR ' | '),'(空)') FROM flyway_schema_history;")
  echo "  ${HISTORY:-（表不存在）}"
  FAILED=$(mysql_q "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;")
  if [ -n "$FAILED" ] && [ "$FAILED" != "0" ]; then
    echo "  >>> 有 $FAILED 条**失败**的迁移记录 → Flyway 会拒绝启动"
  fi
fi

say "5. FK 相关列的整数字符号性（Error 3780 的判定依据）"
SIGNED=$(mysql_q "SELECT IFNULL(GROUP_CONCAT(DISTINCT CONCAT(table_name,'.',column_name,'=',column_type) SEPARATOR ', '),'(无)') FROM information_schema.COLUMNS WHERE table_schema='$MYSQL_DB' AND COLUMN_NAME IN ('dept_id','uploader_id','user_id','role_id','permission_id','video_id','doc_id','applicant_id') AND DATA_TYPE='bigint' AND COLUMN_TYPE NOT LIKE '%unsigned%';")
UNSIGNED=$(mysql_q "SELECT IFNULL(GROUP_CONCAT(DISTINCT CONCAT(table_name,'.',column_name) SEPARATOR ', '),'(无)') FROM information_schema.COLUMNS WHERE table_schema='$MYSQL_DB' AND COLUMN_NAME IN ('dept_id','uploader_id','user_id','role_id','permission_id','video_id','doc_id','applicant_id') AND DATA_TYPE='bigint' AND COLUMN_TYPE LIKE '%unsigned%';")
echo "  signed BIGINT（旧 schema 特征）: ${SIGNED:-（无）}"
echo "  UNSIGNED  （本套 schema 特征）: ${UNSIGNED:-（无）}"
if [ -n "$SIGNED" ] && [ "$SIGNED" != "(无)" ]; then
  echo "  >>> 库里混着旧 schema 的表 → 外键会报 are incompatible（Error 3780）"
  echo "  >>> 解决：重建库（docker compose down -v && docker compose up -d）"
fi

say "6. 结论与下一步"
cat <<'EOF'
按顺序执行（一步到位）：

    cd /var/data/project/edam
    git pull
    ./scripts/dev-reset.sh

它会依次做：
    1. git pull                      拿到最新的迁移脚本与守卫
    2. rm -rf backend/target         清掉已删除的旧迁移残留
    3. docker compose down -v && up -d  重建 MySQL（清掉 initdb.d 建的旧表）
    4. 等 MySQL 就绪
    5. mvn spring-boot:run（端口 8092）

登录：工号 E000001 / 密码 admin123（dev profile 自动创建）

如果 docker 不可用，用等价的手工命令：
    cd /var/data/project/edam && git pull
    rm -rf backend/target
    mysql -h127.0.0.1 -uroot -p -e "DROP DATABASE edam; CREATE DATABASE edam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092
EOF
