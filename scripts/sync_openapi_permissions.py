"""把 Controller 的权限门同步进 doc/openapi.yaml（x-required-permission 扩展）。

为什么需要这个脚本：权限是**数据**（`sys_role_permission` 表）+ **注解**
（`@PreAuthorize`）。OpenAPI 规范里此前完全没有记录「哪个端点要哪个权限码」，
于是规范与实现很容易对不上 —— 而且**不会有人发现**，因为两者都没人看。

做法：
1. 扫所有 Controller（`controller/` + `security/webauthn/`），
   解析出每个端点的 `(METHOD, path)` 与它的 `@PreAuthorize` 权限码；
2. 文本级插入到 openapi.yaml 的对应 operation 里（保留原文件注释与排版，
   不用 yaml.dump 重排 —— 那会把整个 3000 行文件重排一遍，diff 无法审阅）。

用法：
    python scripts/sync_openapi_permissions.py            # 检查，不写
    python scripts/sync_openapi_permissions.py --write     # 写入
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / 'doc' / 'openapi.yaml'

CONTROLLER_DIRS = [
    ROOT / 'backend/src/main/java/com/example/edam/controller',
    ROOT / 'backend/src/main/java/com/example/edam/security/webauthn',
]

# @XxxMapping，可带路径；路径里可能带正则（{filename:.+}）
MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping\s*'
    r'(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?'
)
# 方法签名（4 空格缩进 + public/protected + 左括号）
METHOD_SIG_RE = re.compile(r'^\s{4}(public|protected)\s.*\(')
PERM_RE = re.compile(r"hasAuthority\('([^']+)'\)")
# Spring 的 {filename:.+} → OpenAPI 的 {filename}（规范里 path 参数不允许正则）
NORM_RE = re.compile(r'\{([^}:]+):[^}]+\}')


def norm(path):
    return NORM_RE.sub(r'{\1}', path)


def scan_controllers():
    """返回 {(METHOD, path): 权限码 或 None}"""
    found = {}
    for directory in CONTROLLER_DIRS:
        for java_file in sorted(directory.glob('*Controller.java')):
            lines = java_file.read_text(encoding='utf-8').split('\n')

            # 类级 @RequestMapping 前缀
            class_path = ''
            for line in lines:
                s = line.strip()
                if s.startswith('public class') or s.startswith('public final class'):
                    break
                if s.startswith('@'):
                    m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', s)
                    if m:
                        class_path = m.group(1).rstrip('/')
                        break

            # 逐行扫：@XxxMapping 记下端点，@PreAuthorize 记下权限码，
            # 遇到方法签名说明这个端点的注解收完了。
            current = None
            perm = None
            for line in lines:
                s = line.strip()
                m = MAPPING_RE.search(s)
                if m and s.startswith('@'):
                    sub = m.group(2) or ''
                    current = (m.group(1).upper(),
                               norm(class_path + sub if sub else (class_path or '/')))
                    perm = None
                    continue
                p = PERM_RE.search(s)
                if p:
                    perm = p.group(1)
                    continue
                if METHOD_SIG_RE.match(line):
                    if current is not None:
                        found.setdefault(current, perm)
                    current, perm = None, None
            if current is not None:
                found.setdefault(current, perm)
    return found


PATH_RE = re.compile(r'^  (/[^:]*):\s*$')
OP_RE = re.compile(r'^    (get|post|put|delete|patch):\s*$')
SUMMARY_RE = re.compile(r'^      summary:')


def main():
    table = {k: v for k, v in scan_controllers().items() if v}

    lines = SPEC.read_text(encoding='utf-8').split('\n')
    out = []
    cur_path = cur_method = None
    applied = set()

    for line in lines:
        pm = PATH_RE.match(line)
        if pm:
            cur_path, cur_method = pm.group(1), None
            out.append(line)
            continue
        om = OP_RE.match(line)
        if om:
            cur_method = om.group(1).upper()
            out.append(line)
            continue
        out.append(line)
        if SUMMARY_RE.match(line) and cur_path and cur_method:
            code = table.get((cur_method, cur_path))
            if code:
                out.append(f'      x-required-permission: {code}')
                applied.add((cur_method, cur_path))

    print(f'端点总数（含 WebAuthn）：{len(scan_controllers())}')
    print(f'带 @PreAuthorize 的端点：{len(table)}')
    print(f'本次插入 x-required-permission：{len(applied)}')

    missing = sorted(set(table) - applied)
    if missing:
        print('\nWARNING: 清单里有、但规范里没找到对应 operation：')
        for meth, path in missing:
            print(f'  {meth:6} {path}')

    if '--write' in sys.argv:
        SPEC.write_text('\n'.join(out), encoding='utf-8', newline='\n')
        print(f'\n已写回 {SPEC}')
    else:
        print('\n（未写入，加 --write 才落盘）')


if __name__ == '__main__':
    main()
