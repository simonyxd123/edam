#!/usr/bin/env bash
# 一键跑 4 档位 k6 压测（v3.4 V4-03）
#
# 输出到 perf/k6/results/：
#   - smoke-{TS}.json / load-{TS}.json / peak-{TS}.json / stress-{TS}.json
#   - report-{TS}.html（汇总报告）
#
# 用法：
#   ./scripts/run-all.sh
#   BASE_URL=http://staging.example.com/api/v1 ./scripts/run-all.sh
#   ENV_NAME=staging BASE_URL=... ./scripts/run-all.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RESULT_DIR="${K6_ROOT}/results"
mkdir -p "${RESULT_DIR}"

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
ENV_NAME="${ENV_NAME:-dev}"
TS=$(date +%Y%m%d-%H%M%S)

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}===============================================${NC}"
echo -e "${CYAN} EDAM k6 压测套件（v3.4 V4-03）${NC}"
echo -e "${CYAN} BASE_URL: ${BASE_URL}${NC}"
echo -e "${CYAN} ENV: ${ENV_NAME}${NC}"
echo -e "${CYAN} 时间: ${TS}${NC}"
echo -e "${CYAN}===============================================${NC}"

run_stage() {
  local stage_name=$1
  local script=$2
  local json_file="${RESULT_DIR}/${stage_name}-${TS}.json"

  echo ""
  echo -e "${YELLOW}[${stage_name}] 跑 ${script} ...${NC}"

  if k6 run \
       --out "json=${json_file}" \
       -e BASE_URL="${BASE_URL}" \
       -e ENV_NAME="${ENV_NAME}" \
       "${SCRIPT_DIR}/${script}"; then
    echo -e "${GREEN}✓ ${stage_name} 完成：${json_file}${NC}"
  else
    echo -e "${RED}✗ ${stage_name} 失败（继续跑下一档）${NC}"
  fi
}

# 1. 烟囱测试
run_stage "smoke" "smoke.js"

# 2. 负载测试（SLO 验证）
run_stage "load" "load-test.js"

# 3. 峰值测试
run_stage "peak" "peak-test.js"

# 4. 极限测试
run_stage "stress" "stress-test.js"

# 生成 HTML 报告
echo ""
echo -e "${YELLOW}生成 HTML 报告 ...${NC}"
REPORT_FILE="${RESULT_DIR}/report-${TS}.html"

if command -v python3 >/dev/null 2>&1; then
  python3 "${SCRIPT_DIR}/parse-k6.py" "${RESULT_DIR}" "${REPORT_FILE}"
  echo -e "${GREEN}✓ 报告生成：${REPORT_FILE}${NC}"
else
  echo -e "${RED}⚠ python3 未安装，跳过 HTML 报告生成${NC}"
fi

echo ""
echo -e "${CYAN}===============================================${NC}"
echo -e "${CYAN} 压测完成${NC}"
echo -e "${CYAN} JSON: ${RESULT_DIR}/{smoke,load,peak,stress}-${TS}.json${NC}"
echo -e "${CYAN} HTML: ${REPORT_FILE}${NC}"
echo -e "${CYAN}===============================================${NC}"