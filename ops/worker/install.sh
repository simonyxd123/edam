#!/usr/bin/env bash
# ============================================================================
# EDAM Worker systemd 守护安装脚本（裸机 / 非 K8s 环境）
#
#   sudo ./ops/worker/install.sh                 # 正常安装
#   sudo ./ops/worker/install.sh --stop-existing # 先停掉手工起的 worker 再装
#
# 做五件事：
#   1. 装 unit 文件（渲染模板 → worker + 健康巡检 service/timer）
#   2. 装环境文件模板（已存在则不覆盖）
#   3. enable + start worker，装上每分钟的巡检定时器
#   4. 打印验证命令
#
# 可重复执行（幂等）。**不会**改动已有的 /etc/edam/worker.env。
#
# 为什么模板要渲染（而不是直接 cp 一个写死的 unit）：
#   - **运行用户**：服务器上多半没有 edam 用户（整套东西是 root 跑的），
#     写死 User=edam 会让 systemd 直接起不来
#     （Failed to determine user credentials）。这里按实际情况选。
#   - **路径**：仓库不一定在 /var/data/project/edam。
#   - **解释器**：不一定就是 /usr/bin/python3。
# ============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OPS_DIR="${REPO_ROOT}/ops/worker"
WORKING_DIR="${REPO_ROOT}/worker/src"
UNIT_DIR=/etc/systemd/system
BIN_DIR=/usr/local/bin
STATE_DIR=/var/lib/edam
ENV_DIR=/etc/edam
UNIT_FILE="${UNIT_DIR}/edam-worker.service"

STOP_EXISTING=0
for arg in "$@"; do
    case "$arg" in
        --stop-existing) STOP_EXISTING=1 ;;
        *) echo "未知参数：$arg（可用：--stop-existing）" >&2; exit 2 ;;
    esac
done

log()  { echo "[install] $*"; }
warn() { echo "[install] ⚠️  $*" >&2; }
die()  { echo "[install] ❌ $*" >&2; exit 1; }

if [ "$(id -u)" -ne 0 ]; then
    die "需要 root：sudo $0"
fi

# ---------------------------------------------------------------------------
# 1. 依赖检查
# ---------------------------------------------------------------------------
for cmd in python3 systemctl curl; do
    command -v "$cmd" >/dev/null 2>&1 || die "缺少命令：$cmd"
done
[ -d "$WORKING_DIR" ] || die "找不到 worker 源码目录：$WORKING_DIR"

PYTHON_BIN="$(command -v python3)"

# 解释器能不能 import 到 worker —— 不能的话 unit 永远起不来，
# 与其等到 systemd 报一堆错，不如现在就拦下来并说清楚。
if ! PYTHONPATH="$WORKING_DIR" "$PYTHON_BIN" -c "import edam_worker, edam_worker.main" >/dev/null 2>&1; then
    die "解释器 $PYTHON_BIN 导入 edam_worker 失败。
     多半是依赖没装（aio_pika / fastapi / minio / structlog …）。
     装法：cd $REPO_ROOT/worker && $PYTHON_BIN -m pip install -r requirements.txt
     （若装到了别的解释器上，用那个解释器的绝对路径重跑本脚本）"
fi

# ---------------------------------------------------------------------------
# 2. 渲染并安装 unit
# ---------------------------------------------------------------------------
TEMPLATE="${OPS_DIR}/edam-worker.service.template"
[ -f "$TEMPLATE" ] || die "找不到 unit 模板：$TEMPLATE"

# 运行用户：有 edam 用户就用它（更干净），没有就跑 root（跟现状一致）
if id -u edam >/dev/null 2>&1; then
    SERVICE_USER="edam"
    SERVICE_GROUP="$(id -gn edam)"
    USER_LINE="User=edam"
    GROUP_LINE="Group=${SERVICE_GROUP}"
    log "运行用户：edam"
else
    SERVICE_USER="root"
    SERVICE_GROUP="root"
    USER_LINE=""
    GROUP_LINE=""
    warn "系统里没有 edam 用户 ⇒ worker 将以 root 运行"
    warn "（服务器上整套服务本来就是 root 跑的，保持一致；"
    warn "  想改成专用用户：useradd -r -s /usr/sbin/nologin edam 后重跑本脚本）"
fi

DOCS_URL="file://${REPO_ROOT}/ops/worker/README.md"

# 用 | 作分隔符（路径里不会有 |）。
# User/Group 分成两个占位符 —— sed 的替换文本里放换行不可靠。
sed -e "s|@WORKING_DIR@|${WORKING_DIR}|g" \
    -e "s|@PYTHON_BIN@|${PYTHON_BIN}|g" \
    -e "s|@USER_LINE@|${USER_LINE}|g" \
    -e "s|@GROUP_LINE@|${GROUP_LINE}|g" \
    -e "s|@DOCS_URL@|${DOCS_URL}|g" \
    "$TEMPLATE" > "${UNIT_FILE}.tmp"

# 渲染结果里不该再有占位符 —— 有就说明模板写错了，直接失败
if grep -q '@[A-Z_]*@' "${UNIT_FILE}.tmp"; then
    rm -f "${UNIT_FILE}.tmp"
    die "unit 模板渲染失败，仍有未替换的占位符"
fi

install -m 0644 "${UNIT_FILE}.tmp" "${UNIT_FILE}"
rm -f "${UNIT_FILE}.tmp"
install -m 0644 "${OPS_DIR}/edam-worker-healthcheck.service" "${UNIT_DIR}/edam-worker-healthcheck.service"
install -m 0644 "${OPS_DIR}/edam-worker-healthcheck.timer" "${UNIT_DIR}/edam-worker-healthcheck.timer"
install -m 0755 "${OPS_DIR}/edam-worker-healthcheck.sh" "${BIN_DIR}/edam-worker-healthcheck.sh"
log "unit 文件已安装到 ${UNIT_DIR}"

# ---------------------------------------------------------------------------
# 3. 环境文件
# ---------------------------------------------------------------------------
mkdir -p "${ENV_DIR}" "${STATE_DIR}"
chmod 750 "${STATE_DIR}"
if [ ! -f "${ENV_DIR}/worker.env" ]; then
    install -m 0600 "${OPS_DIR}/edam-worker.env.example" "${ENV_DIR}/worker.env"
    log "已生成 ${ENV_DIR}/worker.env（**请编辑**：至少改 WORKER_TOKEN 和各项密码）"
else
    log "${ENV_DIR}/worker.env 已存在，保持不动"
fi
chmod 0600 "${ENV_DIR}/worker.env"
# worker 以 edam 跑的话，得让它能读这个 0600 的文件
if [ "$SERVICE_USER" != "root" ]; then
    chown "${SERVICE_USER}:${SERVICE_GROUP}" "${ENV_DIR}/worker.env"
fi

# WORKER_TOKEN 还是占位符 ⇒ 回调必然被后端拒（fail-closed）
if grep -q '^WORKER_TOKEN=REPLACE_' "${ENV_DIR}/worker.env" 2>/dev/null; then
    warn "${ENV_DIR}/worker.env 里的 WORKER_TOKEN 还是占位符！"
    warn "后端会拒绝 worker 的回调（401）⇒ 转码完成了 hls_status 也不变 ⇒ 视频播不了。"
    warn "必须改成与后端 edam.worker.token 完全一致的值。"
fi

# ---------------------------------------------------------------------------
# 4. 端口占用检查
#
# 手工 nohup 起的 worker 还在跑的话，systemd 这个会抢不到端口 ⇒
# 反复重启 ⇒ StartLimitBurst 用完 ⇒ unit 停摆，而且日志里全是
# "address already in use"，看不出真正原因。
# ---------------------------------------------------------------------------
HTTP_PORT="$(grep -E '^HTTP_PORT=' "${ENV_DIR}/worker.env" 2>/dev/null | tail -1 | cut -d= -f2 | tr -d '[:space:]')"
HTTP_PORT="${HTTP_PORT:-8001}"

port_busy() {
    "$PYTHON_BIN" - "$1" <<'PY'
import socket, sys
port = int(sys.argv[1])

# 1) 试着 bind：被占（哪怕没在 listen）⇒ 一定冲突
s = socket.socket()
try:
    s.bind(("127.0.0.1", port))
except OSError:
    sys.exit(0)          # 端口被占
finally:
    s.close()

# 2) bind 成功但**连得上** ⇒ 别人在监听（可能是绑在别的地址上，
#    或开了 SO_REUSEADDR —— 各平台语义不同，两步一起看才准）
c = socket.socket()
c.settimeout(2)
try:
    c.connect(("127.0.0.1", port))
except OSError:
    sys.exit(1)          # 连不上 ⇒ 空闲
finally:
    c.close()
sys.exit(0)              # 连得上 ⇒ 占用
PY
}

find_port_pids() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltnpH "sport = :${port}" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u
    elif command -v lsof >/dev/null 2>&1; then
        lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null | sort -u
    elif command -v fuser >/dev/null 2>&1; then
        fuser "${port}/tcp" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' | sort -u
    fi
    return 0
}

if port_busy "$HTTP_PORT"; then
    # `|| true`：找不到 PID 时 grep 会返回 1，而 set -e 下
    # 命令替换失败会让整个脚本直接退出 —— 那正是"端口被占但查不到进程"的情况
    PIDS="$(find_port_pids "$HTTP_PORT" || true)"
    warn "端口 ${HTTP_PORT} 已被占用！"
    [ -n "$PIDS" ] && for p in $PIDS; do
        warn "  PID $p : $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-160)"
    done
    if [ "$STOP_EXISTING" = "1" ] && [ -n "$PIDS" ]; then
        log "--stop-existing：优雅停掉上面这些进程（SIGTERM）"
        for p in $PIDS; do kill -TERM "$p" 2>/dev/null || true; done
        sleep 3
        if port_busy "$HTTP_PORT"; then
            die "停不掉（可能正在转码中，SIGTERM 后要等它收尾）。稍后再试，或手动 kill -9 $PIDS"
        fi
        log "端口已释放"
    else
        die "先停掉占用它的进程，再重跑本脚本：
       方式一（推荐）：sudo ./ops/worker/install.sh --stop-existing
       方式二：手动 kill（先 SIGTERM 优雅停，避免 MinIO 留半成品）：
              kill -TERM $PIDS"
    fi
fi

# ---------------------------------------------------------------------------
# 5. 启用并启动
# ---------------------------------------------------------------------------
systemctl daemon-reload
systemctl enable --now edam-worker.service
systemctl enable --now edam-worker-healthcheck.timer
log "edam-worker 已 enable + start；健康巡检定时器已 enable + start"

# ---------------------------------------------------------------------------
# 6. 验证
# ---------------------------------------------------------------------------
sleep 3
if systemctl is-active --quiet edam-worker.service; then
    log "✅ edam-worker 运行中"
else
    log "❌ edam-worker 没起来，看日志：journalctl -u edam-worker -n 50 --no-pager"
    exit 1
fi

cat <<EOF

接下来：
  1. 编辑 ${ENV_DIR}/worker.env
       WORKER_TOKEN  ← 必须与后端的 edam.worker.token 完全一致
       （后端也要有：后端进程环境变量 WORKER_TOKEN=<同一个值>）
  2. sudo systemctl restart edam-worker
  3. 验证：
       systemctl status edam-worker
       curl -s http://127.0.0.1:${HTTP_PORT}/health/worker | python3 -m json.tool
       journalctl -u edam-worker -f
EOF
