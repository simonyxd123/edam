#!/bin/bash
# ============================================================================
# 启动 API Mock Server（基于 openapi.yaml + Prism）
# 用途：前端开发 / 集成测试 / 离线调试
# ============================================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"
OPENAPI_FILE="$PROJECT_ROOT/doc/openapi.yaml"
PORT="${MOCK_PORT:-4010}"

if [ ! -f "$OPENAPI_FILE" ]; then
  echo "ERROR: openapi.yaml not found at $OPENAPI_FILE"
  exit 1
fi

echo "Starting Prism mock server..."
echo "  OpenAPI: $OPENAPI_FILE"
echo "  Port:    $PORT"
echo "  URL:     http://localhost:$PORT"
echo ""
echo "Usage:"
echo "  curl http://localhost:$PORT/health"
echo "  curl -X POST http://localhost:$PORT/auth/login -H 'Content-Type: application/json' -d '{\"employee_no\":\"admin\",\"password\":\"admin123\"}'"
echo ""

# 使用 Docker 运行 Prism
docker run --rm -it \
  --name edam-mock-api \
  -p $PORT:4010 \
  -v "$OPENAPI_FILE:/tmp/openapi.yaml:ro" \
  stoplight/prism:5 \
  mock -p 4010 -h 0.0.0.0 --dynamic /tmp/openapi.yaml
