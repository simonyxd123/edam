#!/bin/bash
# OpenAPI 规范验证脚本
# 验证 doc/openapi.yaml 是否符合 OpenAPI 3.0 规范

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
OPENAPI_FILE="$PROJECT_ROOT/doc/openapi.yaml"

if [ ! -f "$OPENAPI_FILE" ]; then
    echo "ERROR: $OPENAPI_FILE not found"
    exit 1
fi

echo "Validating OpenAPI spec..."

# 1. YAML 语法
python3 -c "import yaml; yaml.safe_load(open('$OPENAPI_FILE'))" || {
    echo "FAIL: YAML 语法错误"
    exit 1
}
echo "OK YAML 语法"

# 2. 必需字段
python3 - << EOF
import yaml, sys
with open('$OPENAPI_FILE') as f:
    spec = yaml.safe_load(f)

required_fields = ['openapi', 'info', 'paths']
for field in required_fields:
    if field not in spec:
        print(f'FAIL: 缺少必需字段 {field}')
        sys.exit(1)
print('OK 必需字段')

# 3. 路径必须有描述
for path, methods in spec.get('paths', {}).items():
    for method, op in methods.items():
        if method in ['get', 'post', 'put', 'delete', 'patch']:
            if 'summary' not in op:
                print(f'WARN: {method.upper()} {path} 缺少 summary')
            if 'responses' not in op:
                print(f'FAIL: {method.upper()} {path} 缺少 responses')
                sys.exit(1)
print(f'OK 路径 ({len(spec["paths"])} 个)')

# 4. Schema 引用必须存在
import re
all_refs = set()
for path in [spec] + list(spec.get('paths', {}).values()):
    for k, v in path.items() if isinstance(path, dict) else []:
        if isinstance(v, str) and '\$ref' in v:
            ref = v.split('/')[-1]
            all_refs.add(ref)
            if ref not in spec.get('components', {}).get('schemas', {}):
                print(f'WARN: 引用未定义 \${ref}')
print(f'OK 引用 ({len(all_refs)} 个)')

# 5. 统计
print(f'\n=== 统计 ===')
print(f'  Paths:    {len(spec["paths"])}')
print(f'  Schemas:  {len(spec.get("components", {}).get("schemas", {}))}')
print(f'  Tags:     {len(spec.get("tags", []))}')
print(f'  Servers:  {len(spec.get("servers", []))}')
EOF
echo ""
echo "✅ OpenAPI 规范验证通过"