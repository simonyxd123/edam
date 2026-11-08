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
# 整个 @PreAuthorize 表达式（两种引号写法都收）。
# 注意不能要求 @ 紧挨着 PreAuthorize —— 大多数 Controller 写的是全限定名
#   @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('x')")
# 那里 @ 后面还有一长串包名。
PREAUTH_RE = re.compile(r'PreAuthorize\(\s*"([^"]+)"\s*\)')
PREAUTH_SQ_RE = re.compile(r"PreAuthorize\(\s*'([^']+)'\s*\)")


def extract_perm(expr):
    """把 @PreAuthorize 的表达式转成规范里的权限码写法。

    ``hasAuthority('audit:read')``                  → ``audit:read``
    ``hasAuthority('user:read') or hasAuthority('role:read')``
                                                     → ``user:read or role:read``

    为什么要转完整而不是只取第一个 hasAuthority：
    多权限的端点（如"有 user:read 或 role:read 就能看"）如果只记第一个，
    规范里的契约就被**悄悄削弱**了 —— 读规范的人会以为必须同时有两个权限。
    """
    codes = PERM_RE.findall(expr)
    if codes:
        return ' or '.join(codes)
    return expr.strip()


def preauth_of(line):
    """从一行里取出 @PreAuthorize 的表达式（没有则 None）。

    只认**整行就是注解**的情况 —— 注释里出现的 {@code @PreAuthorize(...)}
    是说明文字，不是真的门。
    """
    s = line.strip()
    if not s.startswith('@'):
        return None
    m = PREAUTH_RE.search(s) or PREAUTH_SQ_RE.search(s)
    return m.group(1) if m else None
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

            # 类级 @RequestMapping 前缀 + 类级 @PreAuthorize
            # （类级门覆盖类里所有端点 —— ClassificationController 就是这么写的；
            #  此前扫描器只认方法级，门写在类上时整个类的端点都会被记成"无门"）
            class_path = ''
            class_perm = None
            for line in lines:
                s = line.strip()
                if s.startswith('public class') or s.startswith('public final class'):
                    break
                if s.startswith('@'):
                    if class_perm is None:
                        expr = preauth_of(s)
                        if expr:
                            class_perm = extract_perm(expr)
                    m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', s)
                    if m and not class_path:
                        class_path = m.group(1).rstrip('/')
                    # 注意**不能**在这里 break —— 类级 @PreAuthorize 可能写在
                    # @RequestMapping **之后**（ClassificationController 就是），
                    # 提前 break 会把类级门整个漏掉。

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
                expr = preauth_of(s)
                if expr:
                    perm = extract_perm(expr)
                    continue
                if METHOD_SIG_RE.match(line):
                    if current is not None:
                        found.setdefault(current, perm if perm is not None else class_perm)
                    current, perm = None, None
            if current is not None:
                found.setdefault(current, perm if perm is not None else class_perm)
    return found


PATH_RE = re.compile(r'^  (/[^:]*):\s*$')
OP_RE = re.compile(r'^    (get|post|put|delete|patch):\s*$')
SUMMARY_RE = re.compile(r'^      summary:')
# 权限码可以是 "user:read or role:read" —— 值里有空格，所以不能只匹配 \S+
PERM_LINE_RE = re.compile(r'^(\s*)x-required-permission:\s*(.+?)\s*$')


def find_existing_perm_lines(lines):
    """找出规范里**已经存在**的 x-required-permission 行。

    返回 {行号: (method, path, 权限码)}。

    为什么需要单独扫一遍：规范里的权限行位置不固定（有的是 summary 后面，
    有的是 parameters 前面），而写入逻辑是在 summary 处插入 ——
    不先知道"这个 operation 是不是已经有权限行"，就会插出第二行，
    同一个 operation 出现两个同名 key。YAML 解析器只认最后一个，
    规范文件因此变得不可审阅（这是 --write 之前一直没人用的原因）。
    """
    cur_path = cur_method = None
    found = {}
    for idx, line in enumerate(lines):
        pm = PATH_RE.match(line)
        if pm:
            cur_path, cur_method = pm.group(1), None
            continue
        om = OP_RE.match(line)
        if om:
            cur_method = om.group(1).upper()
            continue
        m = PERM_LINE_RE.match(line)
        if m and cur_path and cur_method:
            found[idx] = (cur_method, cur_path, m.group(2), m.group(1))
    return found


def main():
    table = {k: v for k, v in scan_controllers().items() if v}

    lines = SPEC.read_text(encoding='utf-8').split('\n')
    existing = find_existing_perm_lines(lines)
    ops_with_perm = {(m, p) for (m, p, _c, _i) in existing.values()}

    out = []
    cur_path = cur_method = None
    applied = set()

    for idx, line in enumerate(lines):
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

        # 已经有权限行的 operation：**就地改写**，不重复插入；
        # 对不上门的（端点被删了 / 门被摘了）则把这行删掉 ——
        # 规范里留着一条已经不生效的权限声明，比没有更糟。
        if idx in existing:
            m, p, _old, indent = existing[idx]
            code = table.get((m, p))
            if code:
                out.append(f'{indent}x-required-permission: {code}')
                applied.add((m, p))
            continue

        out.append(line)
        if SUMMARY_RE.match(line) and cur_path and cur_method:
            code = table.get((cur_method, cur_path))
            # 这个 operation 已经有权限行了 → 不在这里再插
            if code and (cur_method, cur_path) not in ops_with_perm:
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
