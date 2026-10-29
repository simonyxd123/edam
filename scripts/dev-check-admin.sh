#!/usr/bin/env bash
# ===================================================================
# 诊断"登录提示工号或密码错误，但数据库里没有用户"
# ===================================================================
# 典型症状：
#   - 前端登录报「工号或密码错误」
#   - SELECT * FROM sys_user;  →  空表
#
# 最常见的原因：初始管理员是由 **应用启动时的 AdminBootstrap** 建的
# （不是由迁移建的）。它插入失败时只记 error 日志、不阻断启动 ——
# 所以服务正常但登不进去。
#
# 插入失败最常见的具体原因：
#   sys_user.dept_id 是 NOT NULL 且无默认值，而初始管理员没有部门归属
#   → "Field 'dept_id' doesn't have a default value"
#   （已由迁移 V20260830_5000 改为可空 —— 前提是这条迁移已执行）
#
# 用法：
#   cd /var/data/project/edam && ./scripts/dev-check-admin.sh
# ===================================================================
# ⚠️ 被 `sh` 调用时自动切回 bash（dash 不支持 `set -o pipefail`，
#    会报 "set: Illegal option -o pipefail"，看起来像脚本坏了）
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
# 对外端口 3307（容器内是 3306）—— 与 dev/docker-compose.yml 一致
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-rootpass}"
MYSQL_DB="${MYSQL_DATABASE:-edam}"

SEP="============================================================"
say() { echo; echo "$SEP"; echo "$*"; echo "$SEP"; }

q() {
  if command -v mysql >/dev/null 2>&1; then
    mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" -N -B "$MYSQL_DB" -e "$1" 2>/dev/null
  elif command -v docker >/dev/null 2>&1; then
    docker exec -i edam-mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" -N -B "$MYSQL_DB" -e "$1" 2>/dev/null
  else
    echo "(连不上数据库：$MYSQL_HOST:$MYSQL_PORT 用户 $MYSQL_USER)"
  fi
}

say "1. Flyway 迁移状态"
HIST=$(q "SELECT IFNULL(GROUP_CONCAT(CONCAT(version,':',IF(success,'ok','FAILED')) ORDER BY installed_rank SEPARATOR ' | '),'(空)') FROM flyway_schema_history;")
echo "  ${HIST:-（查不到）}"
FAILED=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;")
if [ -n "$FAILED" ] && [ "$FAILED" != "0" ]; then
  echo
  echo "  >>> 有 $FAILED 条失败迁移 → Flyway 会拒绝启动。"
  echo "  >>> 解决：./scripts/dev-reset.sh（重建库）"
fi

say "2. 关键迁移是否已应用"
HIT1000=0
HIT5000=0
for v in 20260830.1000 20260830.5000; do
  HIT=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE version='$v' AND success=1;")
  if [ "$HIT" = "1" ]; then
    echo "  $v  ✅ 已应用"
  else
    echo "  $v  ❌ 未应用"
  fi
  if [ "$v" = "20260830.1000" ]; then HIT1000="$HIT"; else HIT5000="$HIT"; fi
done

say "3. sys_user 表的 dept_id 约束（admin 插入失败的头号原因）"
COL=$(q "SELECT IS_NULLABLE, COLUMN_DEFAULT, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$MYSQL_DB' AND TABLE_NAME='sys_user' AND COLUMN_NAME='dept_id';")
echo "  IS_NULLABLE / DEFAULT / TYPE : ${COL:-（列不存在）}"
case "$COL" in
  NO*) echo
        echo "  >>> dept_id 是 NOT NULL —— 初始管理员没有部门归属，插入会报"
        echo "  >>> \"Field 'dept_id' doesn't have a default value\""
        echo "  >>> 解决：确认 V20260830_5000 已应用（见第 2 节），"
        echo "  >>>       或直接执行："
        echo "  >>>       ALTER TABLE sys_user MODIFY COLUMN dept_id BIGINT UNSIGNED NULL;"
        ;;
  *) echo "  ✅ 可空，插入不会因 dept_id 失败" ;;
esac

say "4. sys_user / sys_role 数据"
USERS=$(q "SELECT COUNT(*) FROM sys_user;")
echo "  sys_user 行数 : ${USERS:-?}"
if [ "$USERS" = "0" ]; then
  echo
  echo "  >>> 用户表是空的 → AdminBootstrap 没建成初始管理员。"
fi
ROLES=$(q "SELECT IFNULL(GROUP_CONCAT(code SEPARATOR ', '),'(无)') FROM sys_role;")
echo "  sys_role 角色 : ${ROLES:-?}"
ADMIN_COUNT=$(q "SELECT COUNT(*) FROM sys_user WHERE employee_no='E000001';")
echo "  初始管理员   : $(q "SELECT IFNULL(CONCAT(employee_no,' / ',username),'(不存在)') FROM sys_user WHERE employee_no='E000001';")"

say "5. 后端进程与自举日志"
# 应用日志位置：dev-reset.sh 写 backend/backend.log；手工 nohup 跑写 ./nohup.out
LOG_FOUND=""
for f in backend/backend.log backend/nohup.out nohup.out; do
  if [ -f "$f" ]; then
    LOG_FOUND="$f"
    echo "  --- $f ---"
    grep -iE "admin_bootstrap|default admin|admin user already exists" "$f" 2>/dev/null | tail -5 | sed 's/^/    /'
    grep -iE "doesn.t have a default value|Field .* doesn" "$f" 2>/dev/null | tail -3 | sed 's/^/    /'
  fi
done

# 后端没起来时，把日志尾部直接打出来 ——
# 让人不用再手动 tail 一次、日志再贴一次（省一轮往返）。
# 后端起不来的原因几乎总在最后几十行里（异常堆栈）。
if [ -n "$LOG_FOUND" ]; then
  echo
  echo "  --- $LOG_FOUND 尾部 40 行 ---"
  tail -40 "$LOG_FOUND" | sed 's/^/    /'
  echo
  echo "  --- 关键错误行 ---"
  grep -nE "ERROR|Caused by:|Exception|BUILD FAILURE|Address already in use|Communications link" \
    "$LOG_FOUND" 2>/dev/null | tail -15 | sed 's/^/    /'
fi

say "6. 健康端点里的自举状态"
PORT="${BACKEND_PORT:-8092}"
BOOTSTRAP="not-run"
BOOTSTRAP_ERROR=""
HEALTH=""
# 注意：/health/ready 在 MySQL 不可用时返回 **503**（K8s 探针必须能失败）。
# 这里刻意不加 -f：即使 503，body 里仍有 admin_bootstrap —— 那正是排查
# "服务起来了但登不进去" 时唯一要看的东西。
for u in "http://127.0.0.1:${PORT}/api/v1/health/ready" \
         "http://127.0.0.1:8080/api/v1/health/ready"; do   # 8080 = 旧端口，8092 = 现行
  OUT=$(curl -s --max-time 3 "$u" 2>/dev/null)
  if [ -n "$OUT" ]; then
    HEALTH="$OUT"
    echo "  $u"
    echo "  $OUT" | python -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print('    status          :', d.get('status'))
    for name, c in (d.get('components') or {}).items():
        print('      %-8s     : %s' % (name, c.get('status')))
    print('    admin_bootstrap :', d.get('admin_bootstrap'))
    if d.get('admin_bootstrap_error'):
        print('    error           :', d['admin_bootstrap_error'])
except Exception:
    print('    (非 JSON 输出)')
" 2>/dev/null || echo "    $OUT"
    break
  fi
done

# 从健康端点 JSON 里取出自举状态与错误
if [ -n "$HEALTH" ]; then
  BOOTSTRAP=$(echo "$HEALTH" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get('admin_bootstrap') or 'not-run')
except Exception:
    print('not-run')
" 2>/dev/null || echo "not-run")
  BOOTSTRAP_ERROR=$(echo "$HEALTH" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get('admin_bootstrap_error') or '')
except Exception:
    print('')
" 2>/dev/null || echo "")
fi

say "结论与下一步"

# 把采集到的诊断数据交给判定脚本，输出**唯一结论** + 可直接执行的命令
# （原先是一堆"如果 A 则 B"的分支，跑完还要人自己对着 if 判断）
DEPT_NULLABLE=$(q "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$MYSQL_DB' AND TABLE_NAME='sys_user' AND COLUMN_NAME='dept_id';")

exec python3 "$REPO_ROOT/scripts/dev-check-admin.py" \
  "host=$MYSQL_HOST" \
  "port=$MYSQL_PORT" \
  "db=$MYSQL_DB" \
  "failed_migrations=${FAILED:-0}" \
  "has_5000=$([ "${HIT5000:-0}" = "1" ] && echo 1 || echo 0)" \
  "has_1000=$([ "${HIT1000:-0}" = "1" ] && echo 1 || echo 0)" \
  "dept_nullable=${DEPT_NULLABLE:-}" \
  "user_count=${USERS:-}" \
  "admin_count=${ADMIN_COUNT:-}" \
  "roles=${ROLES:-}" \
  "bootstrap=$BOOTSTRAP" \
  "bootstrap_error=$BOOTSTRAP_ERROR"
