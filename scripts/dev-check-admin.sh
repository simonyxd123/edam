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
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
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
for v in 20260830.1000 20260830.5000; do
  HIT=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE version='$v' AND success=1;")
  if [ "$HIT" = "1" ]; then
    echo "  $v  ✅ 已应用"
  else
    echo "  $v  ❌ 未应用"
  fi
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
echo "  初始管理员   : $(q "SELECT IFNULL(CONCAT(employee_no,' / ',username),'(不存在)') FROM sys_user WHERE employee_no='E000001';")"

say "5. 后端进程与自举日志"
# 应用日志位置：dev-reset.sh 写 backend/backend.log；手工 nohup 跑写 ./nohup.out
for f in backend/backend.log backend/nohup.out nohup.out; do
  if [ -f "$f" ]; then
    echo "  --- $f ---"
    grep -iE "admin_bootstrap|default admin|admin user already exists" "$f" 2>/dev/null | tail -5 | sed 's/^/    /'
    grep -iE "doesn.t have a default value|Field .* doesn" "$f" 2>/dev/null | tail -3 | sed 's/^/    /'
  fi
done

say "6. 健康端点里的自举状态"
PORT="${BACKEND_PORT:-8092}"
for u in "http://127.0.0.1:${PORT}/api/v1/health/ready" \
         "http://127.0.0.1:8080/api/v1/health/ready"; do   # 8080 = 旧端口，8092 = 现行
  OUT=$(curl -s --max-time 3 "$u" 2>/dev/null)
  if [ -n "$OUT" ]; then
    echo "  $u"
    echo "  $OUT" | python -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print('    admin_bootstrap :', d.get('admin_bootstrap'))
    if d.get('admin_bootstrap_error'):
        print('    error           :', d['admin_bootstrap_error'])
except Exception:
    print('    (非 JSON 输出)')
" 2>/dev/null || echo "    $OUT"
    break
  fi
done

say "结论与下一步"
cat <<'EOF'
如果第 3 节显示 dept_id 是 NOT NULL：
    → 直接修：
        mysql -h127.0.0.1 -uroot -p edam -e \
          "ALTER TABLE sys_user MODIFY COLUMN dept_id BIGINT UNSIGNED NULL;"
      然后重启后端，AdminBootstrap 会自动建出 E000001 / admin123。

如果第 2 节显示 V20260830.5000 未应用：
    → ./scripts/dev-reset.sh（重建库 + 跑完整套迁移 + 启动）

如果第 3、4 节都正常但第 6 节显示 admin_bootstrap=failed：
    → 看第 5 节的 error 堆栈，按里面的提示处理
    → 常见：应用跑的是 production profile（不配 edam.bootstrap.admin-password
      就不建账号），或 sys_user 缺列

如果 admin_bootstrap=ok 但用户表仍空：
    → 检查应用连的是不是同一个库（MYSQL_HOST/PORT/DB 与本脚本一致）

账号：工号 E000001 / 密码 admin123（dev profile 自动创建）
EOF
