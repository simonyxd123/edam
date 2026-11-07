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

# 挑一个能用的 python 解释器。
# Windows 上 `python3` 常常解析到应用商店的**占位程序**（Microsoft\WindowsApps\python3），
# 跑起来是 "Permission denied" —— 脚本此前在本机根本跑不起来，
# 而且因为 `set -e`，报错信息还会被误读成"YAML 语法错误"。
PY=""
for candidate in python3 python py; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import yaml" >/dev/null 2>&1; then
        PY="$candidate"
        break
    fi
done
if [ -z "$PY" ]; then
    echo "FAIL: 找不到可用的 python 解释器（需要能 import yaml）"
    exit 1
fi

# Windows 的原生 Python 读不了 MSYS 路径（/g/... ），
# 用 cygpath 转成 Windows 形式再交给 Python。
if command -v cygpath >/dev/null 2>&1; then
    OPENAPI_FILE_PY="$(cygpath -w "$OPENAPI_FILE")"
else
    OPENAPI_FILE_PY="$OPENAPI_FILE"
fi
export OPENAPI_FILE_PY

# 1. YAML 语法
"$PY" -c "import os, yaml; yaml.safe_load(open(os.environ['OPENAPI_FILE_PY'], encoding='utf-8'))" || {
    echo "FAIL: YAML 语法错误"
    exit 1
}
echo "OK YAML 语法"

# 2. 必需字段
"$PY" - << EOF
import os, yaml, sys
with open(os.environ['OPENAPI_FILE_PY'], encoding='utf-8') as f:
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
#
# 这一段此前**从来没生效过**：它只扫每个对象的顶层字符串值，
# 而 `$ref` 永远出现在嵌套的 dict 里 ⇒ 一次都匹配不到，
# 每次都报 "OK 引用 (0 个)" —— 看起来通过了，其实什么都没查。
# 现在递归遍历整棵树，按 JSON Pointer 逐级解析引用。
def walk(node):
    if isinstance(node, dict):
        if '\$ref' in node and isinstance(node['\$ref'], str):
            yield node['\$ref']
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for item in node:
            yield from walk(item)

def resolve(ref):
    """按 JSON Pointer 解析 '#/components/schemas/X' 这类引用。"""
    if not ref.startswith('#/'):
        return True          # 外部引用，本地无法校验
    node = spec
    for part in ref[2:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        if not isinstance(node, dict) or part not in node:
            return False
        node = node[part]
    return True

all_refs = sorted(set(walk(spec)))
missing = [ref for ref in all_refs if not resolve(ref)]
for ref in missing:
    print(f'FAIL: 引用未定义 {ref}')
if missing:
    sys.exit(1)
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