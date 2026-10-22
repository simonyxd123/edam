#!/usr/bin/env bash
# ===================================================================
# 清理后端编译产物（开发环境）
# ===================================================================
# 为什么需要：
#   Maven 的 resources 插件**只复制/覆盖**，不删除源里已移除的资源。
#   本项目删过迁移脚本（backend 的 V1/V2/V3 统一到 V2026*），
#   删掉源文件后，backend/target/classes/db/migration/ 里会**残留**旧脚本。
#   Flyway 随后会同时加载两套 schema：
#     - V3 建的 sys_role_permission 是旧结构
#     - V20260812_1000 的 CREATE TABLE IF NOT EXISTS 静默跳过它
#     - 最后加外键约束时报 Error 3780 "are incompatible"
#   报错位置离病因很远，排查一次要花很久 —— 所以直接清掉。
#
# 用法：
#   ./scripts/dev-clean-backend.sh          # 只删 target
#   ./scripts/dev-clean-backend.sh --run    # 清理后直接启动后端
# ===================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"

echo "==> 清理 $BACKEND_DIR/target"
rm -rf "$BACKEND_DIR/target"

# 顺带清一下可能残留的旧迁移（保险）
if [ -d "$BACKEND_DIR/target" ]; then
  echo "    警告: target 仍存在"
  exit 1
fi

echo "==> 完成。target/classes/db/migration/ 里的陈旧迁移已清除。"
echo "    下次启动后端会从源码重新复制迁移脚本。"

if [ "${1:-}" = "--run" ]; then
  echo "==> 启动后端（开发端口 8092）"
  cd "$BACKEND_DIR"
  exec mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8092
fi
