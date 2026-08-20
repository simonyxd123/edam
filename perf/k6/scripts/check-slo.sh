#!/usr/bin/env bash
# SLO 检查脚本（v3.4 V4-03）
#
# 从 k6 JSON 提取关键指标，与 SLO 阈值比对
# 失败时 exit 1（CI 集成）
#
# 用法：
#   ./check-slo.sh <stage> <json_file>
#   ./check-slo.sh load results/load-20260829.json

set -e

STAGE="${1:-load}"
JSON_FILE="${2:-results/load.json}"

# SLO 阈值（与 parse-k6.py 一致）
declare -A P50_THRESHOLD=( ["smoke"]=200 ["load"]=100 ["peak"]=150 ["stress"]=999999 )
declare -A P95_THRESHOLD=( ["smoke"]=400 ["load"]=200 ["peak"]=300 ["stress"]=999999 )
declare -A P99_THRESHOLD=( ["smoke"]=800 ["load"]=500 ["peak"]=800 ["stress"]=999999 )
declare -A FAIL_THRESHOLD=( ["smoke"]=2 ["load"]=1 ["peak"]=2 ["stress"]=99 )

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

if [ ! -f "${JSON_FILE}" ]; then
  echo -e "${RED}✗ 文件不存在：${JSON_FILE}${NC}"
  exit 1
fi

# 用 python 解析 JSON（jq 可能未安装）
read P50 P95 P99 FAIL_RATE TOTAL RPS < <(python3 - <<EOF
import json
with open("${JSON_FILE}", "r", encoding="utf-8") as f:
    data = json.load(f)
m = data["metrics"]
print(round(m["http_req_duration"]["values"]["p(50)"], 1),
      round(m["http_req_duration"]["values"]["p(95)"], 1),
      round(m["http_req_duration"]["values"]["p(99)"], 1),
      round(m["http_req_failed"]["values"]["rate"] * 100, 2),
      m["http_reqs"]["values"]["count"],
      round(m["http_reqs"]["values"]["rate"], 2))
EOF
)

P50_T=${P50_THRESHOLD[${STAGE}]}
P95_T=${P95_THRESHOLD[${STAGE}]}
P99_T=${P99_THRESHOLD[${STAGE}]}
FAIL_T=${FAIL_THRESHOLD[${STAGE}]}

echo -e "${YELLOW}=== SLO 检查：${STAGE} ===${NC}"
echo "文件: ${JSON_FILE}"
echo "总请求数: ${TOTAL}"
echo "平均 RPS: ${RPS}"
echo "P50: ${P50} ms (阈值 ${P50_T} ms)"
echo "P95: ${P95} ms (阈值 ${P95_T} ms)"
echo "P99: ${P99} ms (阈值 ${P99_T} ms)"
echo "失败率: ${FAIL_RATE} % (阈值 ${FAIL_T} %)"

FAILED=0

check() {
  local name=$1
  local actual=$2
  local threshold=$3
  if [ "$(echo "${actual} > ${threshold}" | bc -l)" = "1" ]; then
    echo -e "${RED}✗ ${name} 未达标：${actual} > ${threshold}${NC}"
    FAILED=$((FAILED + 1))
  else
    echo -e "${GREEN}✓ ${name} 达标：${actual} ≤ ${threshold}${NC}"
  fi
}

# 压测档不检查
if [ "${STAGE}" != "stress" ]; then
  check "P50" "${P50}" "${P50_T}"
  check "P95" "${P95}" "${P95_T}"
  check "P99" "${P99}" "${P99_T}"
  check "失败率" "${FAIL_RATE}" "${FAIL_T}"
fi

if [ ${FAILED} -gt 0 ]; then
  echo -e "${RED}=== SLO 检查失败：${FAILED} 项未达标 ===${NC}"
  exit 1
fi

echo -e "${GREEN}=== SLO 检查通过 ===${NC}"
exit 0