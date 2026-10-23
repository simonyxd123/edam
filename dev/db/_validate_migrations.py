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
    created_once = set()
    problems = []
    warnings = []

    for v, p in files:
        s = p.read_text(encoding='utf-8')
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
                tables[t].add(col)
            print(f"  ~ 表 {t}" + (f" (+{len(added)} 列)" if added else ""))

        # --- CREATE INDEX ---
        for m in re.finditer(r'CREATE INDEX `?\w+`? ON `?(\w+)`?', s, re.I):
            t = m.group(1)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: CREATE INDEX 目标表 {t} 尚不存在")
                print(f"  [错误] INDEX 目标表 {t} 尚不存在")

        # --- INSERT INTO ---
        for m in re.finditer(r'INSERT INTO `?(\w+)`?', s, re.I):
            t = m.group(1)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: INSERT 目标表 {t} 尚不存在")
                print(f"  [错误] INSERT 目标表 {t} 尚不存在")
            else:
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
