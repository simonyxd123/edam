#!/bin/bash
# ============================================================================
# 审计日志 WORM 完整性校验脚本（v3.3 W-1 G-3）
# 等保三级要求：审计日志不可篡改
#
# 策略：
# - 每日凌晨 02:00 校验昨日审计日志完整性
# - 计算日志记录的 SHA-256 hash
# - 与 ES 中存储的 integrity_hash 比对
# - 不一致时触发 P0 告警
# ============================================================================

set -euo pipefail

# 配置
ES_HOST="${ES_HOST:-http://localhost:9200}"
ES_USER="${ES_USER:-elastic}"
ES_PASS="${ES_PASS:-edam_audit_pwd}"
ALERT_WEBHOOK="${ALERT_WEBHOOK:-https://hooks.example.com/alerts}"

# 获取昨日日期
YESTERDAY=$(date -d "yesterday" +"%Y.%m.%d")
INDEX_NAME="edam-audit-${YESTERDAY}"

echo "[$(date)] Starting audit log integrity check for ${INDEX_NAME}..."

# 1. 查询昨日全部审计日志
QUERY='{
  "size": 10000,
  "query": {
    "match_all": {}
  }
}'

RESPONSE=$(curl -sS -u "${ES_USER}:${ES_PASS}" \
  "${ES_HOST}/${INDEX_NAME}/_search" \
  -H 'Content-Type: application/json' \
  -d "${QUERY}")

# 2. 解析 + 校验（每条记录）
TOTAL=$(echo "${RESPONSE}" | jq -r '.hits.total.value')
MISMATCHED=0

for hit in $(echo "${RESPONSE}" | jq -c '.hits.hits[]'); do
  _ID=$(echo "${hit}" | jq -r '._id')
  STORED_HASH=$(echo "${hit}" | jq -r '._source.integrity_hash')

  # 重算 hash（基于原始字段）
  RECALC_HASH=$(echo "${hit}" | jq -r '._source | @tsv | [
    .timestamp, .service, .user_id, .operation, .resource_type, .resource_id, .ip, .result
  ] | join("|")' | sha256sum | awk '{print $1}')

  if [ "${STORED_HASH}" != "${RECALC_HASH}" ]; then
    echo "MISMATCH: _id=${_ID} stored=${STORED_HASH} recalc=${RECALC_HASH}"
    MISMATCHED=$((MISMATCHED + 1))
  fi
done

echo "[$(date)] Total: ${TOTAL}, Mismatched: ${MISMATCHED}"

# 3. 不一致则告警
if [ "${MISMATCHED}" -gt 0 ]; then
  PAYLOAD=$(cat <<EOF
{
  "severity": "P0",
  "title": "审计日志完整性校验失败",
  "detail": "日期 ${YESTERDAY}, 总记录 ${TOTAL}, 不一致 ${MISMATCHED}",
  "index": "${INDEX_NAME}",
  "timestamp": "$(date -Iseconds)"
}
EOF
)

  curl -sS -X POST "${ALERT_WEBHOOK}" \
    -H 'Content-Type: application/json' \
    -d "${PAYLOAD}"

  exit 1
fi

echo "[$(date)] ✅ Audit log integrity OK"