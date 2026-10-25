"""给 dev-check-admin.sh 的结论部分做"自动判定"。

原先脚本末尾是一堆"如果 A 则 B"的分支 —— 跑完还要人自己对着 if 判断，
等于把判断工作又推回给用户。本脚本读同一批诊断数据，给出**唯一结论**
和**可直接执行的命令**。

用法：dev-check-admin.sh 采集数据 → 调本脚本判定
"""

# 由 shell 传入的诊断结果（key=value，空表示查不到）
FACTS = {}


def fact(name, default=""):
    return FACTS.get(name, default).strip()


def main():
    print()
    print("=" * 60)
    print("诊断结论")
    print("=" * 60)

    problems = []
    actions = []

    failed_migrations = fact('failed_migrations')
    has_5000 = fact('has_5000') == '1'
    has_1000 = fact('has_1000') == '1'
    dept_nullable = fact('dept_nullable')
    user_count = fact('user_count')
    admin_count = fact('admin_count')
    bootstrap = fact('bootstrap')
    bootstrap_error = fact('bootstrap_error')
    roles = fact('roles')
    port = fact('port')

    # 1) 连不上库
    if not user_count:
        print("❌ 连不上数据库")
        print(f"   试的是 {fact('host')}:{port} 库 {fact('db')}")
        print()
        print("   排查：")
        print(f"     - docker compose ps | grep mysql   （容器在跑吗）")
        print(f"     - docker port 查实际映射端口")
        print(f"     - MYSQL_PORT 环境变量是不是覆盖了默认值")
        return 1

    # 2) 有失败迁移
    if failed_migrations and failed_migrations != '0':
        problems.append(f"有 {failed_migrations} 条失败的迁移记录")
        actions.append(
            "重建库（Flyway 不会自动清理失败记录）：\n"
            "    ./scripts/dev-reset.sh")

    # 3) 关键迁移没应用
    if not has_5000:
        problems.append("迁移 V20260830_5000（dept_id 改可空）未应用")
        actions.append(
            "重建库并跑完整套迁移：\n"
            "    ./scripts/dev-reset.sh")

    # 4) dept_id 仍 NOT NULL
    #    （对应的 action 在后面统一提到最前，这里只登记问题，避免重复）
    if dept_nullable == 'NO':
        problems.append("sys_user.dept_id 仍是 NOT NULL 无默认值")

    # 5) admin 建出来了但库是空的（不可能，兜底）
    if bootstrap == 'ok' and admin_count == '0':
        problems.append("自举报告成功，但 sys_user 里没有 E000001")
        actions.append(
            "应用连的库和这个脚本查的库不是同一个 —— 对一下后端的\n"
            "MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE：\n"
            "    grep -E 'MYSQL_' backend/src/main/resources/application.yml")

    # 6) 自举失败
    if bootstrap == 'failed':
        problems.append("AdminBootstrap 建初始管理员失败")
        if bootstrap_error:
            print(f"   原始错误：{bootstrap_error}")
            print()
        actions.append(
            "看后端日志里的 admin_bootstrap_failed 段落（里面有完整堆栈）：\n"
            "    grep -A12 admin_bootstrap_failed backend/backend.log")
        if 'dept_id' in bootstrap_error:
            actions.append(
                "错误里提到 dept_id —— 直接把它改成可空（不必重建库）：\n"
                f"    mysql -h127.0.0.1 -P{port} -uroot -p {fact('db')} -e \\\n"
                "      \"ALTER TABLE sys_user MODIFY COLUMN dept_id BIGINT UNSIGNED NULL;\"\n"
                "  然后重启后端")
        else:
            actions.append("按日志里的具体报错处理")

    # 7) 自举根本没跑
    if bootstrap in ('not-run', '未知', ''):
        problems.append("后端没起来，或自举还没跑到（admin_bootstrap=not-run）")
        actions.append(
            "先确认后端在跑：\n"
            "    curl -s http://127.0.0.1:8092/api/v1/health/ready | head -20\n"
            "    tail -50 backend/backend.log")

    # 8) 角色缺失（admin 角色绑不上）
    if roles and 'admin' not in roles:
        problems.append(f"sys_role 里没有 admin 角色（当前：{roles}）")
        actions.append(
            "RBAC 种子迁移没跑或没成功：\n"
            "    ./scripts/dev-reset.sh")

    # 把"直接 ALTER 修 dept_id"提到最前 —— 它代价最小（不用重建库）
    if dept_nullable == 'NO':
        direct = (
            "直接改列（不用重建库，最快的路径）：\n"
            f"    mysql -h127.0.0.1 -P{port} -uroot -p {fact('db')} -e \\\n"
            "      \"ALTER TABLE sys_user MODIFY COLUMN dept_id BIGINT UNSIGNED NULL;\"\n"
            "  然后重启后端，AdminBootstrap 会自动建出 E000001 / admin123")
        actions = [direct] + actions

    # 汇总
    if not problems:
        print("✅ 未发现问题")
        print()
        print(f"   sys_user 行数   : {user_count}")
        print(f"   E000001 命中     : {admin_count}")
        print(f"   sys_role 角色    : {roles}")
        print(f"   admin_bootstrap  : {bootstrap}")
        print()
        print("   如果仍然登录失败，验证密码 hash 是否匹配：")
        print(f"     mysql -h127.0.0.1 -P{port} -uroot -p {fact('db')} -e \\\n"
              "       \"SELECT employee_no, LEFT(password_hash, 10) FROM sys_user;\"")
        print("   以及确认后端连的库与这里一致。")
        return 0

    print(f"发现 {len(problems)} 个问题：")
    for i, p in enumerate(problems, 1):
        print(f"  {i}. {p}")
    print()
    print("=" * 60)
    print("按顺序执行（通常第一条就够）")
    print("=" * 60)
    for i, a in enumerate(actions, 1):
        print(f"\n[{i}] {a}")
    print()
    print("账号：工号 E000001 / 密码 admin123（dev profile 自动创建）")
    return 1


if __name__ == '__main__':
    import os
    import sys

    for arg in sys.argv[1:]:
        if '=' in arg:
            k, v = arg.split('=', 1)
            FACTS[k] = v
    sys.exit(main())
