"""按版本顺序校验迁移链的自洽性。

没有 MySQL 可用时，至少能查出：
  - ALTER / INSERT / CREATE INDEX 指向的表在那个时点还不存在
  - 版本号重复或乱序
  - 改了已应用迁移（这里靠人工比对 git 历史）
"""
import pathlib
import re
import sys

MIG_DIR = pathlib.Path(__file__).resolve().parents[2] / 'backend/src/main/resources/db/migration'


def version_of(path):
    m = re.match(r'^V(\d+)_(\d+)__', path.name)
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)))


def main():
    files = []
    for p in sorted(MIG_DIR.glob('*.sql')):
        v = version_of(p)
        if v is None:
            print(f"跳过（非 V 前缀）: {p.name}")
            continue
        files.append((v, p))
    files.sort(key=lambda x: x[0])

    seen = {}
    for v, p in files:
        if v in seen:
            print(f"[重复版本] {v}: {seen[v].name} 与 {p.name}")
        seen[v] = p

    tables = set()
    problems = []

    for v, p in files:
        s = p.read_text(encoding='utf-8')
        ver = f"{v[0]}.{v[1]}"
        print(f"\n=== {ver}  {p.name} ===")

        # CREATE TABLE
        for m in re.finditer(r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?', s, re.I):
            t = m.group(1)
            if t in tables:
                print(f"  [警告] 表 {t} 已存在（CREATE TABLE IF NOT EXISTS 会跳过）")
            tables.add(t)
            print(f"  + 表 {t}")

        # ALTER TABLE
        for m in re.finditer(r'ALTER TABLE `?(\w+)`?', s, re.I):
            t = m.group(1)
            if t not in tables:
                problems.append(f"[{ver}] ALTER 目标表 {t} 尚不存在")
                print(f"  [错误] ALTER 目标表 {t} 尚不存在")
            else:
                print(f"  ~ 表 {t}")

        # CREATE INDEX
        for m in re.finditer(r'CREATE INDEX `?\w+`? ON `?(\w+)`?', s, re.I):
            t = m.group(1)
            if t not in tables:
                problems.append(f"[{ver}] CREATE INDEX 目标表 {t} 尚不存在")
                print(f"  [错误] INDEX 目标表 {t} 尚不存在")

        # INSERT INTO
        for m in re.finditer(r'INSERT INTO `?(\w+)`?', s, re.I):
            t = m.group(1)
            if t not in tables:
                problems.append(f"[{ver}] INSERT 目标表 {t} 尚不存在")
                print(f"  [错误] INSERT 目标表 {t} 尚不存在")
            else:
                print(f"  + 写入 {t}")

    print(f"\n最终表数: {len(tables)}")
    if problems:
        print(f"\n发现 {len(problems)} 处问题:")
        for x in problems:
            print("  ", x)
        return 1
    print("\n迁移链自洽性检查通过")
    return 0


if __name__ == '__main__':
    sys.exit(main())
