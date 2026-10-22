"""按**真实运行时语义**校验 schema 与 Java model 是否匹配。

关键点：MySQL 的 ``CREATE TABLE IF NOT EXISTS`` 在表已存在时是**空操作**。
同一个表被两个迁移建过（init + 后续迁移）时，最终结构 = **第一次**建的那份
+ 后续所有 ALTER 的增量。

之前的校验器把多次 CREATE 的列取了**并集**，会掩盖真实缺列
（sys_session 就是这么漏掉 6 列的）。本脚本按"先到先得"建模。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIG_DIR = ROOT / 'backend/src/main/resources/db/migration'
MODEL_DIR = ROOT / 'backend/src/main/java/com/example/edam/model'


def version_of(path):
    m = re.match(r'^V(\d+)_(\d+)__', path.name)
    return (int(m.group(1)), int(m.group(2))) if m else None


def build_schema():
    """返回 (表 → 列集合, 问题列表)，按 Flyway 的版本顺序模拟执行。"""
    files = []
    for p in sorted(MIG_DIR.glob('*.sql')):
        v = version_of(p)
        if v:
            files.append((v, p))
    files.sort(key=lambda x: x[0])

    tables = {}          # table → set(columns)
    created_once = set()  # 已经 CREATE 过的表（后续 CREATE IF NOT EXISTS 不生效）
    problems = []

    for v, p in files:
        ver = f"{v[0]}.{v[1]}"
        s = p.read_text(encoding='utf-8')

        for m in re.finditer(
            r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?\s*\((.*?)\n\)\s*ENGINE', s, re.S
        ):
            t, body = m.group(1), m.group(2)
            if t in created_once:
                # IF NOT EXISTS → 空操作，最终结构不变
                problems.append(
                    f"[{ver}] {p.name}: 表 {t} 已被前面的迁移创建，"
                    f"本次 CREATE IF NOT EXISTS 不会生效"
                )
                continue
            cols = set()
            for line in body.split('\n'):
                line = line.strip().rstrip(',')
                if not line or line.startswith('--'):
                    continue
                head = line.split()[0].strip('`')
                if head.upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT', 'INDEX', 'FULLTEXT'):
                    continue
                cols.add(head)
            tables[t] = cols
            created_once.add(t)

        # ALTER 增量
        for m in re.finditer(r'ALTER TABLE `?(\w+)`?(.+?);', s, re.I | re.S):
            t, body = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: ALTER 目标表 {t} 尚不存在")
                continue
            for cm in re.finditer(r'ADD COLUMN `?(\w+)`?', body, re.I):
                tables[t].add(cm.group(1))
            for cm in re.finditer(r'CHANGE COLUMN `?(\w+)`?\s+`?(\w+)`?', body, re.I):
                tables[t].discard(cm.group(1))
                tables[t].add(cm.group(2))
            for cm in re.finditer(r'MODIFY COLUMN `?(\w+)`?', body, re.I):
                tables[t].add(cm.group(1))

    return tables, problems


def snake(n):
    return re.sub(r'([A-Z])', lambda m: '_' + m.group(1).lower(), n).lstrip('_')


def main():
    tables, problems = build_schema()

    print(f"运行时表数: {len(tables)}\n")

    mismatches = []
    checked = 0
    for f in sorted(MODEL_DIR.glob('*.java')):
        s = f.read_text(encoding='utf-8')
        tm = re.search(r'@TableName\("([^"]+)"\)', s)
        if not tm:
            continue
        table = tm.group(1)
        if table not in tables:
            mismatches.append(f"[缺表] {f.name} @TableName({table})")
            continue
        for fm in re.finditer(
            r'@TableField\([^)]*\)\s*private\s+\w+\s+(\w+);|private\s+\w+\s+(\w+);', s
        ):
            field = fm.group(1) or fm.group(2)
            col = 'id' if field == 'id' else snake(field)
            checked += 1
            if col not in tables[table]:
                mismatches.append(f"[缺列] {f.name}: {table}.{col}（字段 {field}）")

    print(f"核对 model 字段: {checked}")
    if mismatches:
        print(f"\n发现 {len(mismatches)} 处 model/schema 不匹配:")
        for x in mismatches:
            print("  ", x)
    else:
        print("✅ 所有 model 字段都能在运行时 schema 里找到")

    if problems:
        print(f"\n迁移链问题 {len(problems)} 处:")
        for x in problems:
            print("  ", x)
    else:
        print("✅ 迁移链无重复建表 / 悬空 ALTER")

    return 1 if (mismatches or problems) else 0


if __name__ == '__main__':
    sys.exit(main())
