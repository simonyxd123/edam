"""按版本顺序校验迁移链的自洽性。

检查项：
  1. ALTER / INSERT / CREATE INDEX 的目标表在那个时点已存在
  2. ALTER TABLE ... ADD COLUMN x ... AFTER <col> 的 <col> 已存在
     （MySQL 语法合法但运行时报
      Error 1054: Unknown column '<col>' in '<table>'）
  3. 版本号不重复、顺序正确
  4. 同一张表被建过两次（CREATE IF NOT EXISTS 是空操作 → 后续列缺失）

不需要真实数据库即可运行。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIG_DIR = ROOT / 'backend/src/main/resources/db/migration'


def strip_sql_comments(sql):
    """剥离 SQL 注释 —— 迁移文件里的说明性注释常带示例 SQL，
    不剥离会被当成真实语句校验 → 误报。"""
    out = []
    for line in sql.splitlines():
        idx = line.find('--')
        if idx >= 0:
            line = line[:idx]
        out.append(line)
    return chr(10).join(out)


def version_of(path):
    m = re.match(r'^V(\d+)_(\d+)__', path.name)
    return (int(m.group(1)), int(m.group(2))) if m else None


def main():
    files = []
    for p in sorted(MIG_DIR.glob('*.sql')):
        v = version_of(p)
        if v:
            files.append((v, p))
    files.sort(key=lambda x: x[0])

    seen = {}
    for v, p in files:
        if v in seen:
            print(f"[重复版本] {v}: {seen[v].name} 与 {p.name}")
        seen[v] = p

    tables = {}        # table -> set(columns)
    added_by = {}      # table -> {column: "ver:migration"} 记录每列是哪来的
    created_once = set()
    problems = []
    warnings = []

    for v, p in files:
        s = strip_sql_comments(p.read_text(encoding='utf-8'))
        ver = f"{v[0]}.{v[1]}"
        print(f"\n=== {ver}  {p.name} ===")

        # --- CREATE TABLE ---
        for m in re.finditer(
            r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?\s*\((.*?)\n\)\s*ENGINE', s, re.S
        ):
            t, body = m.group(1), m.group(2)
            if t in created_once:
                warnings.append(
                    f"[{ver}] {p.name}: 表 {t} 已被前面的迁移创建，"
                    f"本次 CREATE IF NOT EXISTS 不生效（后续 ALTER 需补齐差异列）")
                continue
            cols = set()
            for line in body.split('\n'):
                ls = line.strip().rstrip(',')
                if not ls or ls.startswith('--'):
                    continue
                head = ls.split()[0].strip('`')
                if head.upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT', 'INDEX', 'FULLTEXT'):
                    continue
                cols.add(head)
            tables[t] = cols
            for c in cols:
                added_by.setdefault(t, {})[c] = f"{ver}:{p.name}(init)"
            created_once.add(t)
            print(f"  + 表 {t}")

        # --- ALTER TABLE ---
        for m in re.finditer(r'ALTER TABLE `?(\w+)`?(.+?);', s, re.I | re.S):
            t, body = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: ALTER 目标表 {t} 尚不存在")
                print(f"  [错误] ALTER 目标表 {t} 尚不存在")
                continue
            # ADD COLUMN：记录新列，并校验 AFTER <col> 的依赖
            added = []
            for cm in re.finditer(
                r'ADD COLUMN `?(\w+)`?[^,;]*?(?:AFTER `?(\w+)`?)?\s*(?:,|$)', body, re.I
            ):
                col = cm.group(1)
                after = cm.group(2)
                added.append((col, after))
            for col, after in added:
                if after and after not in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: ALTER {t} ADD COLUMN {col} AFTER {after} —— "
                        f"但 {t}.{after} 在该时点不存在"
                        f"（运行时报 Error 1054: Unknown column '{after}'）")
                    print(f"  [错误] {t}.{after} 不存在，无法 AFTER {after}")
                if col in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: ALTER {t} ADD COLUMN {col} —— "
                        f"该列已存在（{added_by.get(t, {}).get(col, 'init 建表')}）"
                        f"（运行时报 Error 1060: Duplicate column name '{col}'）")
                    print(f"  [错误] {t}.{col} 重复添加")
                tables[t].add(col)
                added_by.setdefault(t, {})[col] = f"{ver}:{p.name}"
            print(f"  ~ 表 {t}" + (f" (+{len(added)} 列)" if added else ""))

        # --- CREATE INDEX ---
        for m in re.finditer(
            r'CREATE INDEX `?\w+`? ON `?(\w+)`?\s*\(([^)]*)\)', s, re.I
        ):
            t, cols = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: CREATE INDEX 目标表 {t} 尚不存在")
                print(f"  [错误] INDEX 目标表 {t} 尚不存在")
            else:
                for c in re.split(r'[,\s]+', cols):
                    c = c.strip().strip('`')
                    if c and c.upper() not in ('INDEX',) and c not in tables[t]:
                        problems.append(
                            f"[{ver}] {p.name}: CREATE INDEX ON {t}({c}) —— "
                            f"列 {t}.{c} 不存在（Error 1072: Key column doesn't exist）")
                        print(f"  [错误] INDEX 列 {t}.{c} 不存在")

        # --- INSERT INTO ---
        # 注意：要校验**显式列清单**里的每一列都存在。
        # 只校验"表存在"是不够的 —— 这正是 V20260830_1000__rbac_seed.sql
        # 踩过的坑：它 INSERT INTO sys_permission (code, name, resource_type,
        # action, description)，而 action/description 两列是
        # V20260830_0100__align_model_columns.sql 才加的 —— 版本号排晚了
        # → 运行时 Error 1054: Unknown column 'action' in 'field list'
        for m in re.finditer(
            r'INSERT(?: IGNORE)? INTO `?(\w+)`?\s*\(([^)]*)\)', s, re.I
        ):
            t, collist = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: INSERT 目标表 {t} 尚不存在")
                print(f"  [错误] INSERT 目标表 {t} 尚不存在")
                continue
            for c in collist.split(','):
                c = c.strip().strip('`')
                if not c or c.upper() == 'IGNORE':
                    continue
                if c not in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: INSERT INTO {t}({c}) —— "
                        f"列 {t}.{c} 在该时点不存在"
                        f"（运行时报 Error 1054: Unknown column '{c}' in 'field list'）")
                    print(f"  [错误] INSERT 列 {t}.{c} 不存在")
            print(f"  + 写入 {t}")

    print(f"\n最终表数: {len(tables)}")
    if problems:
        print(f"\n阻断性问题 {len(problems)} 处:")
        for x in problems:
            print("  ", x)
    else:
        print("\n迁移链自洽性检查通过")
    if warnings:
        print(f"\n需人工确认 {len(warnings)} 处（不阻断）:")
        for x in warnings:
            print("  ", x)

    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
