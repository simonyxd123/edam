#!/usr/bin/env bash
# ============================================================================
# EDAM Worker 健康巡检 + 自愈
#
# 干两件事：
#   1. 进程是否还响应（/health/live）
#      —— 不响应就重启（进程卡死 / 假死）
#   2. 进程活着但**不消费队列**（后端报的 queue_depth > 0 且持续多次）
#      —— 重启（2026-10-28 那次故障的自动修复）
#
# 判定原则（和后端 HealthController 的 liveness 一样）：
#   **只看 worker 自己，不把依赖故障算到 worker 头上。**
#   RabbitMQ 挂了的时候 worker 是无辜的，重启它没用，只会掩盖真正的
#   RabbitMQ 故障。所以这里不判 overall_ok，只判"进程是否响应"。
#
# 状态文件：/var/lib/edam/worker-healthcheck.state
# 环境变量：
#   WORKER_HEALTH_URL      默认 http://127.0.0.1:8001/health/live
#   BACKEND_HEALTH_URL     默认 http://127.0.0.1:8092/api/v1/health/ready
#   BACKEND_HEALTH_TOKEN   可选。后端 /health/ready 是公开端点，通常不用。
#   STUCK_THRESHOLD        连续几次"有积压"才重启，默认 3（约 3 分钟）
#   STATE_FILE             默认 /var/lib/edam/worker-healthcheck.state
# ============================================================================
# ⚠️ 被 `sh` 调用时自动切回 bash（dash 不支持 `set -o pipefail`）
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

set -uo pipefail

WORKER_HEALTH_URL="${WORKER_HEALTH_URL:-http://127.0.0.1:8001/health/live}"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8092/api/v1/health/ready}"
BACKEND_HEALTH_TOKEN="${BACKEND_HEALTH_TOKEN:-}"
STUCK_THRESHOLD="${STUCK_THRESHOLD:-3}"
STATE_FILE="${STATE_FILE:-/var/lib/edam/worker-healthcheck.state}"
SERVICE_NAME="${SERVICE_NAME:-edam-worker}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

state_dir="$(dirname "$STATE_FILE")"
mkdir -p "$state_dir" 2>/dev/null || true
stuck_count=0
if [ -f "$STATE_FILE" ]; then
    stuck_count="$(head -n1 "$STATE_FILE" 2>/dev/null || echo 0)"
    case "$stuck_count" in
        ''|*[!0-9]*) stuck_count=0 ;;
    esac
fi

save_state() { echo "$1" > "$STATE_FILE" 2>/dev/null || true; }

# ---------------------------------------------------------------------------
# 1. 进程还活着吗？
# ---------------------------------------------------------------------------
if ! curl -fsS --max-time 5 "$WORKER_HEALTH_URL" >/dev/null 2>&1; then
    log "worker 不响应（$WORKER_HEALTH_URL）—— 重启 $SERVICE_NAME"
    save_state 0
    systemctl restart "$SERVICE_NAME" || true
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. 活着但队列有积压？—— 连续 N 次就重启
#    队列深度由后端报（后端能直连 RabbitMQ），不用给这个脚本配 RabbitMQ 凭据。
# ---------------------------------------------------------------------------
depth_json="$(curl -fsS --max-time 5 ${BACKEND_HEALTH_TOKEN:+-H "Authorization: Bearer $BACKEND_HEALTH_TOKEN"} \
    "$BACKEND_HEALTH_URL" 2>/dev/null || true)"

if [ -z "$depth_json" ]; then
    # 后端查不到 —— 可能是后端自己挂了，与 worker 无关，不计数、不重启
    log "后端健康端点不可达，跳过积压判定"
    exit 0
fi

# ---------------------------------------------------------------------------
# 2.5 后端的 worker 回调通道通不通？
#
#     后端没配 edam.worker.token（空 = fail-closed = 回调一律 401）时，
#     表现极其迷惑：转码早就完成了、产物也在 MinIO 上，但 hls_status 永远
#     停在 pending ⇒ 用户点播放还是 404，而后端自己完全健康。
#
#     **只告警，不重启 worker** —— 重启解决不了配置问题，
#     而且按本脚本的原则（不把依赖/配置故障算到 worker 头上），
#     不该用重启来"处理"它。
# ---------------------------------------------------------------------------
token_state="$(printf '%s' "$depth_json" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    cb = data.get("worker_callback") or {}
    print("%s|%s" % (cb.get("status", "unknown"), cb.get("token_configured", "unknown")))
except Exception:
    print("unknown|unknown")
' 2>/dev/null || echo 'unknown|unknown')"

case "$token_state" in
    misconfigured*)
        log "⚠️ 后端没配 edam.worker.token —— worker 的回调会被拒（401），"
        log "   转码完成后 hls_status 也不会更新（用户点播放仍会 404）。"
        log "   修法：后端进程加 WORKER_TOKEN=<值>，worker 的 WORKER_TOKEN 与之完全一致。"
        ;;
esac

depth="$(printf '%s' "$depth_json" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    print(int((data.get("rabbitmq") or {}).get("queue_depth") or 0))
except Exception:
    print(0)
' 2>/dev/null || echo 0)"

if [ "${depth:-0}" -gt 0 ]; then
    stuck_count=$((stuck_count + 1))
    log "队列积压 $depth 条（连续第 $stuck_count 次）"
    save_state "$stuck_count"
    if [ "$stuck_count" -ge "$STUCK_THRESHOLD" ]; then
        log "连续 $stuck_count 次有积压但 worker 活着 —— 判定为不消费，重启 $SERVICE_NAME"
        save_state 0
        systemctl restart "$SERVICE_NAME" || true
        exit 1
    fi
else
    if [ "$stuck_count" -gt 0 ]; then
        log "队列已清空，重置计数"
    fi
    save_state 0
fi

exit 0
