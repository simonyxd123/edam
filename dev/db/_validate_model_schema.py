"""按**真实运行时语义**校验 schema 与 Java model 是否匹配。

关键点：MySQL 的 ``CREATE TABLE IF NOT EXISTS`` 在表已存在时是**空操作**。
同一个表被两个迁移建过（init + 后续迁移）时，最终结构 = **第一次**建的那份
+ 后续所有 ALTER 的增量。

之前的校验器把多次 CREATE 的列取了**并集**，会掩盖真实缺列
（sys_session 就是这么漏掉 7 列的）。本脚本按"先到先得"建模。

检查维度：
  1. ALTER / INSERT / CREATE INDEX 的目标表在那个时点已存在
  2. ALTER TABLE ... ADD COLUMN x ... AFTER <col> 的 <col> 已存在
  3. ADD COLUMN 重复（Error 1060 Duplicate column name）
  4. CREATE INDEX 的列已存在（Error 1072 Key column doesn't exist）
  5. INSERT INTO t (col1, col2) 里**显式列清单**的每列都已存在
     （Error 1054 Unknown column in 'field list'）
  6. model 的**可空字段**（包装类型）对应的列在 schema 里却是
     NOT NULL 且无 DEFAULT —— 插入时若不设该字段会报
     "Field 'x' doesn't have a default value"（作为警告）

不需要真实数据库即可运行。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIG_DIR = ROOT / 'backend/src/main/resources/db/migration'
MODEL_DIR = ROOT / 'backend/src/main/java/com/example/edam/model'

# 只关注**数值型包装类型**（Long / Integer）—— 它们通常是指向别的表的
# 可选关联（deptId / uploaderId / userId / roleId / tagId …），
# 业务上确实存在"这个字段不设"的场景。
#
# 不看 String / Boolean：它们在实践中总是会赋值（username / title / name），
# 全报出来会把真正的问题淹没在噪音里。
NULLABLE_NUMERIC = {'Long', 'Integer', 'Short', 'Byte'}


def strip_sql_comments(sql: str) -> str:
    """剥离 SQL 注释。

    必须做：迁移文件里常有"说明性"的注释段落，里面会带示例 SQL
    （比如解释某条语句报什么错）。不剥离的话，校验器会把注释里的
    示例当成真实语句去校验 → 误报。
    """
    out = []
    for line in sql.splitlines():
        # 去掉行内 -- 注释（迁移里没有含 '--' 的字符串字面量）
        idx = line.find('--')
        if idx >= 0:
            line = line[:idx]
        out.append(line)
    return "\n".join(out)


def version_of(path):
    m = re.match(r'^V(\d+)_(\d+)__', path.name)
    return (int(m.group(1)), int(m.group(2))) if m else None


def parse_create_body(body):
    """解析 CREATE TABLE 的列定义 → (列集合, NOT NULL且无DEFAULT的列集合)"""
    cols = set()
    not_null = set()
    for line in body.split('\n'):
        ls = line.strip().rstrip(',')
        if not ls or ls.startswith('--'):
            continue
        head = ls.split()[0].strip('`')
        if head.upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT', 'INDEX', 'FULLTEXT'):
            continue
        cols.add(head)
        up = ls.upper()
        if 'NOT NULL' in up and 'DEFAULT' not in up:
            not_null.add(head)
    return cols, not_null


def build_schema():
    """按版本顺序模拟执行，返回 (表→列, 表→NOT NULL列, 问题, 警告)"""
    files = []
    for p in sorted(MIG_DIR.glob('*.sql')):
        v = version_of(p)
        if v:
            files.append((v, p))
    files.sort(key=lambda x: x[0])

    tables = {}          # table → set(columns)
    not_null_cols = {}   # table → set(列名)：NOT NULL 且无 DEFAULT
    created_once = set()
    problems = []
    warnings = []

    for v, p in files:
        s = strip_sql_comments(p.read_text(encoding='utf-8'))
        ver = f"{v[0]}.{v[1]}"

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
            cols, nn = parse_create_body(body)
            tables[t] = cols
            not_null_cols[t] = nn
            created_once.add(t)

        # --- ALTER TABLE ---
        for m in re.finditer(r'ALTER TABLE `?(\w+)`?(.+?);', s, re.I | re.S):
            t, body = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: ALTER 目标表 {t} 尚不存在")
                continue
            for cm in re.finditer(
                r'ADD COLUMN `?(\w+)`?([^,;]*?)(?:AFTER `?(\w+)`?)?\s*(?:,|$)', body, re.I
            ):
                col, coldef, after = cm.group(1), cm.group(2) or '', cm.group(3)
                if after and after not in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: ALTER {t} ADD COLUMN {col} AFTER {after} —— "
                        f"但 {t}.{after} 在该时点不存在"
                        f"（运行时报 Error 1054: Unknown column '{after}'）")
                if col in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: ALTER {t} ADD COLUMN {col} —— 该列已存在"
                        f"（运行时报 Error 1060: Duplicate column name '{col}'）")
                tables[t].add(col)
                up = coldef.upper()
                if 'NOT NULL' in up and 'DEFAULT' not in up:
                    not_null_cols.setdefault(t, set()).add(col)
                else:
                    not_null_cols.setdefault(t, set()).discard(col)
            for cm in re.finditer(r'CHANGE COLUMN `?(\w+)`?\s+`?(\w+)`?([^,;]*)', body, re.I):
                old, new, coldef = cm.group(1), cm.group(2), cm.group(3) or ''
                tables[t].discard(old)
                tables[t].add(new)
                up = coldef.upper()
                if 'NOT NULL' in up and 'DEFAULT' not in up:
                    not_null_cols.setdefault(t, set()).add(new)
                else:
                    not_null_cols.setdefault(t, set()).discard(new)
            for cm in re.finditer(r'MODIFY COLUMN `?(\w+)`?([^,;]*)', body, re.I):
                col, coldef = cm.group(1), cm.group(2) or ''
                tables[t].add(col)
                up = coldef.upper()
                if 'NOT NULL' in up and 'DEFAULT' not in up:
                    not_null_cols.setdefault(t, set()).add(col)
                else:
                    not_null_cols.setdefault(t, set()).discard(col)

        # --- CREATE INDEX ---
        for m in re.finditer(
            r'CREATE INDEX `?\w+`? ON `?(\w+)`?\s*\(([^)]*)\)', s, re.I
        ):
            t, cols = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: CREATE INDEX 目标表 {t} 尚不存在")
                continue
            for c in re.split(r'[,\s]+', cols):
                c = c.strip().strip('`')
                if c and c.upper() != 'INDEX' and c not in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: CREATE INDEX ON {t}({c}) —— 列 {t}.{c} 不存在"
                        f"（Error 1072: Key column doesn't exist）")

        # --- INSERT INTO（校验显式列清单）---
        for m in re.finditer(
            r'INSERT(?: IGNORE)? INTO `?(\w+)`?\s*\(([^)]*)\)', s, re.I
        ):
            t, collist = m.group(1), m.group(2)
            if t not in tables:
                problems.append(f"[{ver}] {p.name}: INSERT 目标表 {t} 尚不存在")
                continue
            for c in collist.split(','):
                c = c.strip().strip('`')
                if not c or c.upper() == 'IGNORE':
                    continue
                if c not in tables[t]:
                    problems.append(
                        f"[{ver}] {p.name}: INSERT INTO {t}({c}) —— 列 {t}.{c} 在该时点不存在"
                        f"（Error 1054: Unknown column '{c}' in 'field list'）")

    return tables, not_null_cols, problems, warnings


def snake(n):
    return re.sub(r'([A-Z])', lambda m: '_' + m.group(1).lower(), n).lstrip('_')


def model_fields(java_src):
    """返回 [(字段名, Java 类型)]"""
    out = []
    for fm in re.finditer(
        r'(?:@TableField\([^)]*\)\s*)?private\s+([\w<>,\[\]\. ]+?)\s+(\w+);', java_src
    ):
        out.append((fm.group(2), fm.group(1).strip()))
    return out


def main():
    tables, not_null_cols, problems, warnings = build_schema()

    print(f"运行时表数: {len(tables)}\n")

    mismatches = []
    nullability = {}
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
        for field, jtype in model_fields(s):
            col = 'id' if field == 'id' else snake(field)
            checked += 1
            if col not in tables[table]:
                mismatches.append(f"[缺列] {f.name}: {table}.{col}（字段 {field}）")
                continue
            # 维度 6：model 可空（数值包装类型）但 schema NOT NULL 且无默认值
            base = jtype.split('<')[0].split('.')[-1]
            if col == 'id':
                continue    # 自增主键由 DB 生成，不会成为插入失败点
            if base in NULLABLE_NUMERIC and col in not_null_cols.get(table, set()):
                nullability.setdefault(table, []).append(f"{col}（model: {jtype}）")

    print(f"核对 model 字段: {checked}")
    if mismatches:
        print(f"\n发现 {len(mismatches)} 处 model/schema 不匹配:")
        for x in mismatches:
            print("  ", x)
    else:
        print("✅ 所有 model 字段都能在运行时 schema 里找到")

    if problems:
        print(f"\n迁移链阻断性问题 {len(problems)} 处:")
        for x in problems:
            print("  ", x)
    else:
        print("✅ 迁移链无悬空 ALTER / 悬空 INSERT / 缺列引用")

    if nullability:
        total = sum(len(v) for v in nullability.values())
        print(f"\n可空性不一致 {total} 处（model 说可空 / schema 说 NOT NULL 无默认值）:")
        print("  这些列**不是**当前一定失败，但任何不设该字段的 INSERT 都会报")
        print('  "Field \'x\' doesn\'t have a default value"（MySQL 严格模式）。')
        print("  典型触发点：AdminBootstrap 建初始管理员时没有部门归属。")
        for tbl, cols in sorted(nullability.items()):
            print(f"    {tbl}: {', '.join(cols)}")

    if warnings:
        print(f"\n需人工确认 {len(warnings)} 处（不阻断）:")
        for x in warnings:
            print("  ", x)

    return 1 if (mismatches or problems) else 0


if __name__ == '__main__':
    sys.exit(main())
