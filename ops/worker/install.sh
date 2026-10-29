#!/usr/bin/env bash
# ============================================================================
# EDAM Worker systemd 守护安装脚本（裸机 / 非 K8s 环境）
#
#   sudo ./ops/worker/install.sh
#
# 做四件事：
#   1. 装 unit 文件（worker + 健康巡检 service/timer）
#   2. 装环境文件模板（已存在则不覆盖）
#   3. enable + start worker，装上每分钟的巡检定时器
#   4. 打印验证命令
#
# 可重复执行（幂等）。**不会**改动已有的 /etc/edam/worker.env。
# ============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OPS_DIR="${REPO_ROOT}/ops/worker"
UNIT_DIR=/etc/systemd/system
BIN_DIR=/usr/local/bin
STATE_DIR=/var/lib/edam
ENV_DIR=/etc/edam

log() { echo "[install] $*"; }

if [ "$(id -u)" -ne 0 ]; then
    echo "需要 root：sudo $0" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 1. 依赖检查
# ---------------------------------------------------------------------------
for cmd in python3 systemctl curl; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "缺少命令：$cmd" >&2; exit 1; }
done
if ! python3 -c "import edam_worker" >/dev/null 2>&1; then
    log "警告：当前用户/环境的 python3 找不到 edam_worker 模块。"
    log "      unit 里写死了 /usr/bin/python3 和工作目录，"
    log "      如果 Worker 装在别处，请先改 edam-worker.service。"
fi

# ---------------------------------------------------------------------------
# 2. 单元文件
# ---------------------------------------------------------------------------
install -m 0644 "${OPS_DIR}/edam-worker.service" "${UNIT_DIR}/edam-worker.service"
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
    log "  WORKER_TOKEN 必须与后端的 edam.worker.token 一致，否则转码完成了状态也不会变"
else
    log "${ENV_DIR}/worker.env 已存在，保持不动"
fi
chmod 0600 "${ENV_DIR}/worker.env"

# ---------------------------------------------------------------------------
# 4. 启用并启动
# ---------------------------------------------------------------------------
systemctl daemon-reload
systemctl enable --now edam-worker.service
systemctl enable --now edam-worker-healthcheck.timer
log "edam-worker 已 enable + start；健康巡检定时器已 enable + start"

# ---------------------------------------------------------------------------
# 5. 验证
# ---------------------------------------------------------------------------
sleep 3
if systemctl is-active --quiet edam-worker.service; then
    log "✅ edam-worker 运行中"
else
    log "❌ edam-worker 没起来，看日志：journalctl -u edam-worker -n 50 --no-pager"
    exit 1
fi

cat <<'EOF'

接下来：
  1. 编辑 /etc/edam/worker.env（WORKER_TOKEN / 密码 / 地址）
  2. sudo systemctl restart edam-worker
  3. 验证：
       systemctl status edam-worker
       curl -s http://127.0.0.1:8001/health/worker | python3 -m json.tool
       journalctl -u edam-worker -f
EOF
