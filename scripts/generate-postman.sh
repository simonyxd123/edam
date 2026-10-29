#!/bin/bash
# ============================================================================
# 生成 Postman Collection
# 基于 doc/openapi.yaml 用 openapi-generator 生成
# 替代手工维护的 dev/mock/postman_collection.json
# ============================================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
OPENAPI_FILE="$PROJECT_ROOT/doc/openapi.yaml"
OUTPUT_DIR="$PROJECT_ROOT/dev/mock"
TEMP_DIR=$(mktemp -d)

GENERATOR_VERSION="7.5.0"

GREEN='\033[0;32m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $1"; }

if [ ! -f "$OPENAPI_FILE" ]; then
    echo "ERROR: $OPENAPI_FILE not found"
    exit 1
fi

# 下载 openapi-generator-cli
log "下载 openapi-generator-cli $GENERATOR_VERSION..."
curl -L "https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/$GENERATOR_VERSION/openapi-generator-cli-$GENERATOR_VERSION.jar" \
    -o "$TEMP_DIR/openapi-generator-cli.jar"

# 生成 Postman Collection
log "生成 Postman Collection..."
java -jar "$TEMP_DIR/openapi-generator-cli.jar" generate \
    -i "$OPENAPI_FILE" \
    -g postman-collection \
    -o "$OUTPUT_DIR" \
    --additional-properties=\
collectionName="EDAM API (v3.1)",\
baseUrl=http://localhost:4010,\
language=en \
    --skip-validate-spec \
    2>&1 | tail -10

# 重命名输出文件
if [ -f "$OUTPUT_DIR/EDAM API v3.1.postman_collection.json" ]; then
    mv "$OUTPUT_DIR/EDAM API v3.1.postman_collection.json" "$OUTPUT_DIR/postman_collection.json"
fi

log "Postman Collection 已生成: $OUTPUT_DIR/postman_collection.json"

# 生成 Environment 文件
cat > "$OUTPUT_DIR/postman_environment.json" << 'EOF'
{
  "id": "edam-env-local",
  "name": "EDAM Local (Prism Mock)",
  "values": [
    {"key": "baseUrl", "value": "http://localhost:4010", "enabled": true},
    {"key": "token", "value": "", "enabled": true}
  ],
  "_postman_variable_scope": "environment"
}
EOF

log "Postman Environment 已生成: $OUTPUT_DIR/postman_environment.json"

# 同时输出 openapi 环境（连接真实 API）
cat > "$OUTPUT_DIR/postman_environment_prod.json" << 'EOF'
{
  "id": "edam-env-prod",
  "name": "EDAM Production",
  "values": [
    {"key": "baseUrl", "value": "https://api.example.com/api/v1", "enabled": true},
    {"key": "token", "value": "", "enabled": true}
  ],
  "_postman_variable_scope": "environment"
}
EOF

# 清理
rm -rf "$TEMP_DIR"
log "完成"

echo ""
echo "导入步骤："
echo "1. 打开 Postman → File → Import"
echo "2. 选择 dev/mock/postman_collection.json"
echo "3. 选择 dev/mock/postman_environment.json（local 或 prod）"
echo "4. 开始测试 API"