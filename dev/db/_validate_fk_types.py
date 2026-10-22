"""静态校验：全新库上，init 里的每个外键约束两端类型是否兼容。

MySQL 要求 FK 两端列的**类型与符号性**完全一致
（例：signed BIGINT 引用 unsigned BIGINT → Error 3780 "are incompatible"）。

本脚本按"第一次 CREATE 生效 + 后续 ALTER 增量"模拟出全新库的最终结构，
再逐条检查 init 末尾的 ADD CONSTRAINT ... FOREIGN KEY。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIG_DIR = ROOT / 'backend/src/main/resources/db/migration'
INIT = MIG_DIR / 'V20260812_1000__init_schema.sql'


def parse_columns(body):
    """解析 CREATE TABLE 的列定义 → {列名: 完整类型串}"""
    cols = {}
    for line in body.split('\n'):
        ls = line.strip().rstrip(',')
        if not ls or ls.startswith('--'):
            continue
        head = ls.split()[0].strip('`')
        if head.upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT', 'INDEX', 'FULLTEXT'):
            continue
        # 取到类型部分（去掉 NOT NULL / DEFAULT / COMMENT 等修饰）
        parts = ls.split()
        typ = parts[1] if len(parts) > 1 else ''
        unsigned = 'UNSIGNED' in ls.upper()
        cols[head] = (typ.upper(), unsigned)
    return cols


def base_type(t):
    """BIGINT UNSIGNED → BIGINT；VARCHAR(64) → VARCHAR"""
    return t.split('(')[0].strip().upper()


def main():
    # 1) 按版本顺序模拟出最终结构
    files = []
    for p in sorted(MIG_DIR.glob('*.sql')):
        m = re.match(r'^V(\d+)_(\d+)__', p.name)
        if m:
            files.append(((int(m.group(1)), int(m.group(2))), p))
    files.sort(key=lambda x: x[0])

    tables = {}
    for _, p in files:
        s = p.read_text(encoding='utf-8')
        for m in re.finditer(
            r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?\s*\((.*?)\n\)\s*ENGINE', s, re.S
        ):
            t, body = m.group(1), m.group(2)
            if t in tables:      # CREATE IF NOT EXISTS → 空操作
                continue
            tables[t] = parse_columns(body)
        for m in re.finditer(r'ALTER TABLE `?(\w+)`?(.+?);', s, re.I | re.S):
            t, body = m.group(1), m.group(2)
            if t not in tables:
                continue
            for cm in re.finditer(r'ADD COLUMN `?(\w+)`?\s+(\w+(?:\(\d+\))?)', body, re.I):
                tables[t][cm.group(1)] = (cm.group(2).upper(), 'UNSIGNED' in body.upper()
                                          and cm.group(2).upper().endswith('UNSIGNED'))
            for cm in re.finditer(r'CHANGE COLUMN `?(\w+)`?\s+`?(\w+)`?\s+(\w+(?:\(\d+\))?)', body, re.I):
                tables[t].pop(cm.group(1), None)
                tables[t][cm.group(2)] = (cm.group(3).upper(),
                                          'UNSIGNED' in cm.group(0).upper())

    # 2) 逐条检查 init 里的 FK
    init = INIT.read_text(encoding='utf-8')
    fks = re.finditer(
        r'ALTER TABLE\s+`?(\w+)`?\s+ADD CONSTRAINT\s+`?(\w+)`?\s+FOREIGN KEY\s*\(`?(\w+)`?\)\s*'
        r'REFERENCES\s+`?(\w+)`?\s*\(`?(\w+)`?\)',
        init, re.I)

    problems = []
    count = 0
    for m in fks:
        child_tab, fk_name, child_col, parent_tab, parent_col = m.groups()
        count += 1
        if child_tab not in tables:
            problems.append(f"[缺表] {fk_name}: {child_tab} 不存在")
            continue
        if parent_tab not in tables:
            problems.append(f"[缺表] {fk_name}: {parent_tab} 不存在")
            continue
        cc = tables[child_tab].get(child_col)
        pc = tables[parent_tab].get(parent_col)
        if cc is None:
            problems.append(f"[缺列] {fk_name}: {child_tab}.{child_col} 不存在")
            continue
        if pc is None:
            problems.append(f"[缺列] {fk_name}: {parent_tab}.{parent_col} 不存在")
            continue
        if base_type(cc[0]) != base_type(pc[0]):
            problems.append(
                f"[类型不同] {fk_name}: {child_tab}.{child_col} {cc[0]}"
                f"{'(UNSIGNED)' if cc[1] else '(signed)'}  →  "
                f"{parent_tab}.{parent_col} {pc[0]}{'(UNSIGNED)' if pc[1] else '(signed)'}")
        elif cc[1] != pc[1]:
            problems.append(
                f"[符号性不同] {fk_name}: {child_tab}.{child_col} "
                f"{'UNSIGNED' if cc[1] else 'signed'} → "
                f"{parent_tab}.{parent_col} {'UNSIGNED' if pc[1] else 'signed'}  (Error 3780)")

    print(f"检查外键约束: {count} 条")
    if problems:
        print(f"\n发现 {len(problems)} 处不兼容:")
        for x in problems:
            print("  ", x)
        return 1
    print("✅ 全部外键两端类型兼容（全新库可正常建约束）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
