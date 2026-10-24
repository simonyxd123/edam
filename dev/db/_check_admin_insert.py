"""核对 AdminBootstrap 的插入路径与 sys_user 表的约束是否匹配。

AdminBootstrap 建初始管理员时如果漏了某个「NOT NULL 且无默认值」的列，
插入就会报：

    Field 'xxx' doesn't have a default value

而这个失败在上一轮被降级成了「只记 error 日志、不阻断启动」——
于是应用照常起来，但管理员账号没建出来，用户只看到
「工号或密码错误」+ 空的用户表。

本脚本把这条链路静态跑一遍，提前发现漏列。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIG = ROOT / 'backend/src/main/resources/db/migration'
BOOTSTRAP = ROOT / 'backend/src/main/java/com/example/edam/bootstrap/AdminBootstrap.java'
MODEL = ROOT / 'backend/src/main/java/com/example/edam/model/SysUser.java'


def norm(n):
    return re.sub(r'([A-Z])', lambda m: '_' + m.group(1).lower(), n).lstrip('_')


def table_columns(table):
    """从迁移脚本里解析出某张表的最终列定义（列名 → 是否 NOT NULL 无默认值）"""
    cols = {}
    created = False
    for p in sorted(MIG.glob('*.sql')):
        s = p.read_text(encoding='utf-8')
        for m in re.finditer(
            r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?\s*\((.*?)\n\)\s*ENGINE', s, re.S
        ):
            if m.group(1) != table:
                continue
            if created:
                continue    # CREATE IF NOT EXISTS 是空操作
            created = True
            for line in m.group(2).split('\n'):
                ls = line.strip().rstrip(',')
                if not ls or ls.startswith('--'):
                    continue
                head = ls.split()[0].strip('`')
                if head.upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT', 'INDEX', 'FULLTEXT'):
                    continue
                up = ls.upper()
                required = 'NOT NULL' in up and 'DEFAULT' not in up \
                    and 'AUTO_INCREMENT' not in up
                cols[head] = required
        # ALTER 增量
        for m in re.finditer(r'ALTER TABLE `?(\w+)`?(.+?);', s, re.I | re.S):
            if m.group(1) != table:
                continue
            body = m.group(2)
            for cm in re.finditer(
                r'(ADD|MODIFY|CHANGE)\s+(?:COLUMN\s+)?`?(\w+)`?\s*`?(\w+)?`?([^,;]*)', body, re.I
            ):
                action, old, new, rest = cm.groups()
                name = new if (action.upper() == 'ADD' and new) else (old if action.upper() == 'CHANGE' else old)
                if not name:
                    continue
                up = (rest or '').upper()
                if action.upper() == 'ADD':
                    cols[name] = ('NOT NULL' in up and 'DEFAULT' not in up
                                  and 'AUTO_INCREMENT' not in up)
                else:
                    cols[name] = ('NOT NULL' in up and 'DEFAULT' not in up
                                  and 'AUTO_INCREMENT' not in up)
    return cols


def main():
    cols = table_columns('sys_user')
    if not cols:
        print('!! 未能解析 sys_user 表定义')
        return 1

    # AdminBootstrap 里 setXxx(...) 的调用 → 列名
    src = BOOTSTRAP.read_text(encoding='utf-8')
    setter_to_col = {
        'setUsername': 'username', 'setPasswordHash': 'password_hash',
        'setEmployeeNo': 'employee_no', 'setRealName': 'real_name',
        'setEmail': 'email', 'setPhone': 'phone', 'setDeptId': 'dept_id',
        'setStatus': 'status', 'setMfaSecret': 'mfa_secret',
        'setMfaEnabled': 'mfa_enabled', 'setLastLoginAt': 'last_login_at',
        'setLastLoginIp': 'last_login_ip',
        'setFailedLoginCount': 'failed_login_count',
        'setMustChangePassword': 'must_change_password',
        'setPasswordChangedAt': 'password_changed_at',
        'setDeletedAt': 'deleted_at', 'setCreatedAt': 'created_at',
        'setUpdatedAt': 'updated_at',
    }
    set_cols = set()
    for m in re.finditer(r'admin\.(set\w+)\(', src):
        name = m.group(1)
        if name in setter_to_col:
            set_cols.add(setter_to_col[name])

    required = {c for c, req in cols.items() if req}
    # 自增主键与 DB 默认填充的列不算
    auto = {c for c, req in cols.items()
            if 'AUTO_INCREMENT' in str(cols.get(c))}
    missing = sorted(required - set_cols - {'id'})

    print('sys_user 的必填列（NOT NULL 且无默认值）:')
    for c in sorted(required):
        mark = '✓ AdminBootstrap 设置了' if c in set_cols else '✗ 未设置'
        print(f'  {c:24} {mark}')
    print()
    if missing:
        print(f'!! AdminBootstrap 漏设 {len(missing)} 个必填列: {missing}')
        print('   → 插入会报 "Field \'x\' doesn\'t have a default value"')
        print('   → 需要新建迁移把这些列改成可空，或在 AdminBootstrap 里补设')
        return 1
    print('✅ AdminBootstrap 设置了全部必填列')
    return 0


if __name__ == '__main__':
    sys.exit(main())
