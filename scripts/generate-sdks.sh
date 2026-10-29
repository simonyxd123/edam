#!/bin/bash
# ============================================================================
# SDK 客户端生成脚本
# 基于 doc/openapi.yaml 自动生成 Java/TypeScript/Python SDK
# 使用 openapi-generator-cli
# ============================================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
OPENAPI_FILE="$PROJECT_ROOT/doc/openapi.yaml"
SDK_DIR="$PROJECT_ROOT/sdk"
GENERATOR_VERSION="7.5.0"
TEMP_DIR=$(mktemp -d)

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# 检查前置依赖
check_prereq() {
    if [ ! -f "$OPENAPI_FILE" ]; then
        echo "ERROR: openapi.yaml not found at $OPENAPI_FILE"
        exit 1
    fi

    if ! command -v java &> /dev/null; then
        echo "ERROR: Java 11+ required. Install with: apt install default-jre / brew install openjdk"
        exit 1
    fi

    if ! command -v npm &> /dev/null; then
        warn "npm not found, skipping TypeScript SDK generation"
    fi

    if ! command -v python3 &> /dev/null; then
        warn "python3 not found, skipping Python SDK generation"
    fi
}

# 下载 openapi-generator-cli
download_generator() {
    if [ ! -f "$TEMP_DIR/openapi-generator-cli.jar" ]; then
        log "下载 openapi-generator-cli $GENERATOR_VERSION..."
        curl -L "https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/$GENERATOR_VERSION/openapi-generator-cli-$GENERATOR_VERSION.jar" \
            -o "$TEMP_DIR/openapi-generator-cli.jar"
    fi
}

# ============================================================================
# 1. 生成 Java SDK
# ============================================================================
gen_java() {
    log "生成 Java SDK..."
    java -jar "$TEMP_DIR/openapi-generator-cli.jar" generate \
        -i "$OPENAPI_FILE" \
        -g java \
        -o "$SDK_DIR/java" \
        --library native \
        --additional-properties=\
dateLibrary=java8,\
artifactId=edam-client,\
groupId=com.example.edam,\
artifactVersion=3.1.0,\
useJakartaEe=true,\
serializationLibrary=jackson,\
hideGenerationTimestamp=true \
        --skip-validate-spec \
        2>&1 | tail -20

    log "Java SDK 已生成：$SDK_DIR/java"
}

# ============================================================================
# 2. 生成 TypeScript SDK
# ============================================================================
gen_typescript() {
    if ! command -v npm &> /dev/null; then
        warn "跳过 TypeScript SDK（npm 未安装）"
        return
    fi

    log "生成 TypeScript SDK..."
    java -jar "$TEMP_DIR/openapi-generator-cli.jar" generate \
        -i "$OPENAPI_FILE" \
        -g typescript-fetch \
        -o "$SDK_DIR/typescript" \
        --additional-properties=\
npmName=@edam/client,\
npmVersion=3.1.0,\
supportsES6=true,\
typescriptThreePlus=true,\
useSingleRequestParameter=true \
        --skip-validate-spec \
        2>&1 | tail -20

    log "TypeScript SDK 已生成：$SDK_DIR/typescript"
}

# ============================================================================
# 3. 生成 Python SDK
# ============================================================================
gen_python() {
    if ! command -v python3 &> /dev/null; then
        warn "跳过 Python SDK（python3 未安装）"
        return
    fi

    log "生成 Python SDK..."
    java -jar "$TEMP_DIR/openapi-generator-cli.jar" generate \
        -i "$OPENAPI_FILE" \
        -g python \
        -o "$SDK_DIR/python" \
        --library urllib3 \
        --additional-properties=\
packageName=edam_client,\
packageVersion=3.1.0,\
projectName=edam-client,\
usePydantic=true \
        --skip-validate-spec \
        2>&1 | tail -20

    log "Python SDK 已生成：$SDK_DIR/python"
}

# ============================================================================
# 4. 生成 README
# ============================================================================
gen_readme() {
    cat > "$SDK_DIR/README.md" << 'EOF'
# EDAM SDK 客户端

本目录由 `scripts/generate-sdks.sh` 从 `doc/openapi.yaml` 自动生成。

## ⚠️ 不要手动编辑

SDK 代码由工具生成，手动修改会被下次生成覆盖。

如需定制：
1. 修改 `doc/openapi.yaml` 后重新运行脚本
2. 或使用 `gitOps.patch.json` 应用补丁

## 生成方法

```bash
# 一键生成所有 SDK
./scripts/generate-sdks.sh

# 仅生成 Java
./scripts/generate-sdks.sh java

# 仅生成 TypeScript
./scripts/generate-sdks.sh typescript
```

## 使用示例

### Java

```java
import com.example.edam.ApiClient;
import com.example.edam.api.AuthApi;
import com.example.edam.model.LoginRequest;

ApiClient client = new ApiClient();
client.setBasePath("https://api.example.com/api/v1");
client.setBearerToken("eyJhbGc...");

AuthApi auth = new AuthApi(client);
LoginRequest req = new LoginRequest();
req.setEmployeeNo("SA0001");
req.setPassword("admin123");
LoginResponse resp = auth.authLogin(req);
```

### TypeScript

```typescript
import { Configuration, AuthApi, LoginRequest } from '@edam/client';

const config = new Configuration({
  basePath: 'https://api.example.com/api/v1',
  accessToken: 'eyJhbGc...'
});

const auth = new AuthApi(config);
const req: LoginRequest = {
  employee_no: 'SA0001',
  password: 'admin123'
};
const resp = await auth.authLogin(req);
```

### Python

```python
import edam_client
from edam_client.api import auth_api
from edam_client.model.login_request import LoginRequest

configuration = edam_client.Configuration(
    host="https://api.example.com/api/v1",
    access_token="eyJhbGc..."
)

with edam_client.ApiClient(configuration) as api_client:
    auth = auth_api.AuthApi(api_client)
    req = LoginRequest(employee_no="SA0001", password="admin123")
    resp = auth.auth_login(req)
```

## 版本管理

| SDK | openapi-generator | 说明 |
| --- | --- | --- |
| Java | 7.5.0 | 原生 HTTP client + Jackson |
| TypeScript | 7.5.0 | Fetch API，支持 ES6+ |
| Python | 7.5.0 | urllib3 + Pydantic |

每次发布新版本时，重新运行生成脚本并提交。
EOF
    log "SDK README 已生成"
}

# ============================================================================
# 主流程
# ============================================================================
case "${1:-all}" in
    java) gen_java ;;
    typescript) gen_typescript ;;
    python) gen_python ;;
    all|"")
        check_prereq
        download_generator
        mkdir -p "$SDK_DIR"
        gen_java
        gen_typescript
        gen_python
        gen_readme
        log "所有 SDK 已生成至 $SDK_DIR"
        ;;
    *)
        echo "Usage: $0 {all|java|typescript|python}"
        exit 1
        ;;
esac

# 清理
rm -rf "$TEMP_DIR"
log "完成"