#!/usr/bin/env bash
# ============================================================================
# EDAM 批量补跑：把 hls_status != ready 的视频重新丢回队列
#
# **为什么需要这个**：2026-10-28 起 worker 一直没起来（详见
# modify/2026-10-29-fix-worker-startup-crash.md），队列没人消费 ⇒
# 视频全部卡在 pending。等 worker 修好之后，这些消息**已经被丢弃**
# （重试用尽 ⇒ ACK 掉），队列里不会再有它们 ⇒ 必须主动重新触发。
#
# 逐条手工 curl 十几条太累，也容易漏 —— 这个脚本从数据库把待处理的
# 视频捞出来，逐条走 worker 的运维接口重新入队。
#
# 用法：
#   sudo ./ops/worker/reprocess-pending.sh              # 干跑，只列出
#   sudo ./ops/worker/reprocess-pending.sh --execute     # 真的入队
#
# 环境变量：
#   WORKER_OPS_URL   默认 http://127.0.0.1:8001
#   OPS_TOKEN        worker 的 X-Ops-Token（必需）
#   MYSQL_HOST/PORT/DATABASE/USER/PASSWORD  查询用
#   ONLY_ID          只处理某个 video_id
#   SINCE            只处理 upload_time 晚于该时间的（默认 '2026-10-01'）
#   BATCH_DELAY      每条之间的间隔秒数（默认 2，给 worker 留呼吸时间）
# ============================================================================
# ⚠️ 被 `sh` 调用时自动切回 bash（dash 不支持 `set -o pipefail`）
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

set -uo pipefail

WORKER_OPS_URL="${WORKER_OPS_URL:-http://127.0.0.1:8001}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_DATABASE="${MYSQL_DATABASE:-edam}"
MYSQL_USER="${MYSQL_USER:-edam}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-edampass}"
ONLY_ID="${ONLY_ID:-}"
SINCE="${SINCE:-2026-10-01}"
BATCH_DELAY="${BATCH_DELAY:-2}"

EXECUTE=0
for arg in "$@"; do
    case "$arg" in
        --execute) EXECUTE=1 ;;
        --dry-run) EXECUTE=0 ;;
        *) echo "未知参数：$arg（用 --execute 真正入队）" >&2; exit 2 ;;
    esac
done

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# ---------------------------------------------------------------------------
# 1. ops token 从哪来
# ---------------------------------------------------------------------------
# worker 的 token 在 /etc/edam/worker.env 里（chmod 600）。
# 不从命令行参数读 —— 那会进 shell history。
if [ -z "${OPS_TOKEN:-}" ]; then
    for f in /etc/edam/worker.env /etc/edam/edam-worker.env; do
        if [ -r "$f" ]; then
            OPS_TOKEN="$(grep -E '^OPS_TOKEN=' "$f" | head -n1 | cut -d= -f2-)"
            [ -n "$OPS_TOKEN" ] && break
        fi
    done
fi

if [ -z "${OPS_TOKEN:-}" ]; then
    log "✗ 找不到 OPS_TOKEN —— 设 OPS_TOKEN 环境变量，或确认 /etc/edam/worker.env 里有"
    exit 1
fi

if ! curl -fsS --max-time 5 "${WORKER_OPS_URL}/ops/health" >/dev/null 2>&1; then
    log "✗ worker 的 ops 接口不响应（${WORKER_OPS_URL}/ops/health）"
    log "  先确认 worker 起来了：systemctl status edam-worker"
    exit 1
fi

ops_health="$(curl -fsS --max-time 5 "${WORKER_OPS_URL}/ops/health")"
# 只取 true/false —— 字段值后面可能跟着 } 或 ,
ops_enabled="$(printf '%s' "$ops_health" \
    | grep -o '"ops_enabled":[[:space:]]*\(true\|false\)' \
    | head -n1 | sed 's/.*:[[:space:]]*//')"
if [ "$ops_enabled" != "true" ]; then
    log "✗ worker 的 OPS_ENABLED=false —— 运维端点没开"
    log "  在 /etc/edam/worker.env 里设 OPS_ENABLED=true 再重启 worker"
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. 从数据库捞出待处理的视频
# ---------------------------------------------------------------------------
# hls_status: 0=pending 1=processing 2=ready 3=failed
# 只捞 hls_status != 2 的（ready 的重跑没意义，纯浪费转码资源）。
# 同时要求 minio_path 非空 —— 那是 worker 下载原片的依据。
where="hls_status <> 2 AND minio_path IS NOT NULL AND minio_path <> '' AND deleted_at IS NULL"
if [ -n "$ONLY_ID" ]; then
    where="${where} AND id = ${ONLY_ID}"
fi
where="${where} AND upload_time >= '${SINCE}'"

log "查询条件：${where}"
# MYSQL_PWD 而不是 -p"$MYSQL_PASSWORD" —— 后者会把密码暴露在 ps 的命令行里
rows="$(MYSQL_PWD="$MYSQL_PASSWORD" mysql \
    -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" \
    -D "$MYSQL_DATABASE" -N -B -e \
    "SELECT id, minio_path, hls_status FROM video_resource WHERE ${where} ORDER BY id;" 2>&1)"
mysql_rc=$?

if [ "$mysql_rc" -ne 0 ]; then
    log "✗ 数据库查询失败：${rows}"
    exit 1
fi

if [ -z "$rows" ]; then
    log "✓ 没有待处理的视频 —— 全部 ready"
    exit 0
fi

count="$(echo "$rows" | wc -l | tr -d ' ')"
log "找到 ${count} 条待处理的视频"
[ "$EXECUTE" -eq 1 ] && log "模式：--execute（真的入队）" || log "模式：干跑（加 --execute 才入队）"

# ---------------------------------------------------------------------------
# 3. 逐条重新入队
# ---------------------------------------------------------------------------
ok=0
fail=0
while IFS=$'\t' read -r vid minio_path hls_status; do
    [ -z "${vid:-}" ] && continue
    case "$vid" in ''|*[!0-9]*) continue ;; esac

    status_name="unknown"
    case "$hls_status" in
        0) status_name="pending" ;;
        1) status_name="processing" ;;
        3) status_name="failed" ;;
    esac

    if [ "$EXECUTE" -eq 0 ]; then
        log "  [干跑] video_id=${vid}  hls_status=${status_name}  input_path=${minio_path}"
        continue
    fi

    body="{\"input_path\": \"${minio_path}\", \"classification_lv\": \"L2\"}"
    resp="$(curl -fsS --max-time 20 -X POST \
        "${WORKER_OPS_URL}/ops/videos/${vid}/reprocess-hls" \
        -H "X-Ops-Token: ${OPS_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "$body" 2>&1)"
    curl_rc=$?

    if [ "$curl_rc" -eq 0 ]; then
        log "  ✓ video_id=${vid} 已入队"
        ok=$((ok + 1))
    else
        log "  ✗ video_id=${vid} 入队失败：${resp}"
        fail=$((fail + 1))
    fi

    # 给 worker 一点喘息时间 —— 一次性灌几十条会把转码队列压垮
    [ "$BATCH_DELAY" -gt 0 ] && sleep "$BATCH_DELAY"
done <<< "$rows"

if [ "$EXECUTE" -eq 1 ]; then
    log "完成：成功 ${ok} 条，失败 ${fail} 条"
    log "看进度：journalctl -u edam-worker -f -o cat | grep -E 'hls_transcode_complete|video_processing_complete'"
else
    log "干跑结束。确认无误后加 --execute 再跑一次。"
fi
