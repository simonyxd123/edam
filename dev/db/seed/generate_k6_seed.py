#!/usr/bin/env python3
"""
k6 测试用户 seed 生成器（v3.4 V4-03）

生成 dev/db/seed/V20260829_2000__seed_k6_users.sql：
- k6_tester 角色（id=5），授予全部 read 类权限
- 100 个 k6 测试用户（TEST_001 ~ TEST_NNN）
- 全部用户关联 k6_tester 角色 + dept_id=5（后端组）

依赖：
    pip install bcrypt

用法：
    python3 generate_k6_seed.py > V20260829_2000__seed_k6_users.sql
    python3 generate_k6_seed.py --users 200 > V20260829_2000__seed_k6_users.sql
"""

import argparse
import sys
from datetime import datetime

# 强制 UTF-8 输出（Windows 默认 cp936/GBK）
try:
    sys.stdout.reconfigure(encoding="utf-8")
except (AttributeError, OSError):
    pass

try:
    import bcrypt
except ImportError:
    print("错误: 需要安装 bcrypt 模块", file=sys.stderr)
    print("运行: pip install bcrypt", file=sys.stderr)
    sys.exit(1)


# 固定密码（CI 环境请确保 dev/staging 使用同一密码）
PASSWORD = b"TestP@ssw0rd!"
DEPT_ID = 5  # 后端组（dev/db/seed/V20260812_2000__seed_baseline.sql 中已存在）
ROLE_K6_TESTER_ID = 5  # k6_tester 角色 ID（避免与 baseline 4 个角色冲突）


# k6_tester 角色授予的权限（覆盖 8 大类 read 类接口）
K6_PERMISSIONS = [
    "video:read",
    "video:download",
    "video:distribute",
    "document:read",
    "document:download",
    "document:distribute",
    "system:audit_export",
]


def gen_bcrypt_hash(password: bytes, rounds: int = 10) -> str:
    """生成 bcrypt hash"""
    return bcrypt.hashpw(password, bcrypt.gensalt(rounds=rounds)).decode()


def render_sql(user_count: int) -> str:
    """渲染完整 SQL"""
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    password_hash = gen_bcrypt_hash(PASSWORD)
    # 由于 bcrypt 每次 salt 不同，100 用户共享同一 hash（同一密码即可）
    # 注：seed 文件重新生成时 hash 会变，这是 bcrypt 安全性的一部分

    lines = []
    lines.append("-- ============================================================================")
    lines.append("-- V20260829_2000__seed_k6_users.sql")
    lines.append(f"-- k6 压测测试用户 seed（{user_count} 个 TEST_NNN）")
    lines.append(f"-- 生成时间: {now}")
    lines.append("-- 用途: v3.4 V4-03 k6 压测（参见 perf/k6/README.md）")
    lines.append("-- 关联 modify: 2026-08-29-V4-03-烟囱测试执行指南.md")
    lines.append("-- ")
    lines.append("-- 重新生成命令:")
    lines.append("--   python3 generate_k6_seed.py > V20260829_2000__seed_k6_users.sql")
    lines.append("-- ")
    lines.append("-- 密码: TestP@ssw0rd!（仅 dev/staging 使用）")
    lines.append("-- ============================================================================")
    lines.append("")
    lines.append("SET NAMES utf8mb4;")
    lines.append("")

    # 1. k6_tester 角色（idempotent）
    lines.append("-- 1. k6_tester 角色（id=5）")
    lines.append("INSERT IGNORE INTO sys_role (id, code, name, description, is_system) VALUES")
    lines.append(f"    ({ROLE_K6_TESTER_ID}, 'k6_tester', 'k6 压测专用', '用于 k6 压测场景的虚拟用户角色', 1);")
    lines.append("")

    # 2. 角色权限关联
    lines.append("-- 2. k6_tester 角色权限（覆盖 8 大类 read 类接口）")
    lines.append("INSERT IGNORE INTO sys_role_permission (role_id, permission_id)")
    lines.append(f"SELECT {ROLE_K6_TESTER_ID}, id FROM sys_permission WHERE code IN (")
    for i, perm in enumerate(K6_PERMISSIONS):
        comma = "," if i < len(K6_PERMISSIONS) - 1 else ""
        lines.append(f"    '{perm}'{comma}")
    lines.append(");")
    lines.append("")

    # 3. k6 测试用户
    lines.append(f"-- 3. k6 测试用户（{user_count} 个，password_hash 共享）")
    lines.append("INSERT IGNORE INTO sys_user (")
    lines.append("    username, password_hash, employee_no, real_name, email,")
    lines.append("    dept_id, status, mfa_enabled, version")
    lines.append(") VALUES")
    for i in range(1, user_count + 1):
        employee_no = f"TEST_{i:03d}"
        comma = "," if i < user_count else ";"
        lines.append(
            f"    ('{employee_no}', '{password_hash}', '{employee_no}', "
            f"'K6 Test {i:03d}', 'k6_{i:03d}@perf.local', "
            f"{DEPT_ID}, 1, 0, 0){comma}"
        )
    lines.append("")

    # 4. 用户角色关联（关联 k6_tester）
    lines.append(f"-- 4. k6 用户角色关联（每个用户关联 k6_tester 角色）")
    lines.append("INSERT IGNORE INTO sys_user_role (user_id, role_id, granted_at)")
    lines.append("SELECT u.id, " + str(ROLE_K6_TESTER_ID) + ", NOW(3)")
    lines.append("FROM sys_user u")
    lines.append(f"WHERE u.employee_no LIKE 'TEST_%'")
    lines.append(f"  AND u.id NOT IN (SELECT user_id FROM sys_user_role WHERE role_id = {ROLE_K6_TESTER_ID});")
    lines.append("")

    # 5. 清理辅助视图（用于验证）
    lines.append("-- 5. 验证视图（可选，用于 CI 快速核对 seed 成功）")
    lines.append("CREATE OR REPLACE VIEW v_k6_seed_status AS")
    lines.append("SELECT")
    lines.append("    (SELECT COUNT(*) FROM sys_role WHERE id = " + str(ROLE_K6_TESTER_ID) + ") AS role_exists,")
    lines.append("    (SELECT COUNT(*) FROM sys_role_permission WHERE role_id = " + str(ROLE_K6_TESTER_ID) + f") AS role_perms,")
    lines.append(f"    (SELECT COUNT(*) FROM sys_user WHERE employee_no LIKE 'TEST_%') AS k6_users,")
    lines.append(f"    (SELECT COUNT(*) FROM sys_user_role ur JOIN sys_user u ON ur.user_id = u.id")
    lines.append(f"     WHERE u.employee_no LIKE 'TEST_%' AND ur.role_id = {ROLE_K6_TESTER_ID}) AS k6_role_assigned;")
    lines.append("")
    lines.append("-- 验证查询:")
    lines.append("--   SELECT * FROM v_k6_seed_status;")
    lines.append("--   -- 期望: role_exists=1, role_perms=7, k6_users=100, k6_role_assigned=100")
    lines.append("")
    lines.append("-- ============================================================================")
    lines.append("-- k6 seed 生成完成")
    lines.append("-- 重要: 默认密码 TestP@ssw0rd! 仅用于 dev/staging 环境，prod 严禁使用")
    lines.append("-- ============================================================================")
    lines.append("")

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="k6 测试用户 seed 生成器")
    parser.add_argument(
        "--users",
        type=int,
        default=100,
        help="生成用户数量（默认 100，CI 大规模压测可设 500）",
    )
    parser.add_argument(
        "--rounds",
        type=int,
        default=10,
        help="bcrypt cost（默认 10，dev 可用 4 加速）",
    )
    args = parser.parse_args()

    sql = render_sql(args.users)
    print(sql)


if __name__ == "__main__":
    main()