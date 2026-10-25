"""校验 MySQL 端口在各处是否一致。

EDAM 的 MySQL 端口有两层：
  - **容器内**：3306（MySQL 默认）
  - **宿主机/开发环境对外**：3307（dev/docker-compose.yml 的端口映射）

此前 compose 是 `3306:3306`，而 application.yml 的 MYSQL_PORT 默认
3307、三个诊断脚本又写 3306 —— 三方不一致。后果：
  - 应用连 3307，脚本查 3306 → **诊断脚本看的是另一个库**
  - 宿主机 3306 端口若被占用，compose 直接起不来

CI 两种模式要分开对待：
  - GitLab CI：service 与 job **同网络** → 用 service 名 + 容器端口 3306
  - GitHub Actions：service 通过**端口映射**暴露 → job 内用 localhost:3307
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

CONTAINER_PORT = '3306'
HOST_PORT = '3307'

COMPOSE = ROOT / 'dev/docker-compose.yml'
APP_YML = ROOT / 'backend/src/main/resources/application.yml'
HELM_VALUES = ROOT / 'helm/edam/values.yaml'
GITLAB_CI = ROOT / '.gitlab-ci.yml'
GITHUB_CI = ROOT / '.github/workflows/ci.yml'
SCRIPTS = ['dev-reset.sh', 'dev-diagnose.sh', 'dev-check-admin.sh']


def read(p):
    return p.read_text(encoding='utf-8') if p.exists() else ''


def service_block(text, name):
    """取 compose 里某个服务段的文本（到下一个同缩进的服务名为止）。"""
    lines = text.split('\n')
    start = None
    for i, ln in enumerate(lines):
        if re.match(r'^  ' + name + r':\s*$', ln):
            start = i + 1
            break
    if start is None:
        return ''
    out = []
    for ln in lines[start:]:
        if re.match(r'^  [a-z]\w*:\s*$', ln):
            break
        out.append(ln)
    return '\n'.join(out)


def main():
    errors = []
    print('MySQL 端口来源：')

    # 1) compose 的端口映射 "宿主:容器"
    mysql_svc = service_block(read(COMPOSE), 'mysql')
    # ports: 与实际映射之间可能夹着注释行 → 用 [\s\S]*? 跨越
    m = re.search(r'ports:[\s\S]*?-\s*"(\d+):(\d+)"', mysql_svc)
    if not m:
        errors.append("compose 的 mysql 服务里找不到 ports 映射")
    else:
        host, container = m.group(1), m.group(2)
        ok = (host == HOST_PORT and container == CONTAINER_PORT)
        mark = '  [OK]' if ok else '  [ERR] 期望 ' + HOST_PORT + ':' + CONTAINER_PORT
        print(f'  {host}:{container}  compose mysql ports{mark}')
        if not ok:
            errors.append(f'compose 端口映射应为 {HOST_PORT}:{CONTAINER_PORT}，实际 {host}:{container}')

    # 2) application.yml 的 MYSQL_PORT 默认值（= 宿主机端口）
    m = re.search(r'MYSQL_PORT:(\d+)', read(APP_YML))
    if not m:
        errors.append('application.yml 里找不到 MYSQL_PORT')
    else:
        p = m.group(1)
        ok = p == HOST_PORT
        mark = '  [OK]' if ok else '  [ERR] 期望 ' + HOST_PORT
        print(f'  {p}  application.yml MYSQL_PORT{mark}')
        if not ok:
            errors.append(f'application.yml 的 MYSQL_PORT 应为 {HOST_PORT}，实际 {p}')

    # 3) 三个脚本的 MYSQL_PORT 默认值
    for name in SCRIPTS:
        m = re.search(r'MYSQL_PORT="\$\{MYSQL_PORT:-(\d+)\}"', read(ROOT / 'scripts' / name))
        if not m:
            errors.append(f'scripts/{name} 里找不到 MYSQL_PORT 默认值')
            continue
        p = m.group(1)
        ok = p == HOST_PORT
        mark = '  [OK]' if ok else '  [ERR] 期望 ' + HOST_PORT
        print(f'  {p}  scripts/{name}{mark}')
        if not ok:
            errors.append(f'scripts/{name} 的 MYSQL_PORT 应为 {HOST_PORT}，实际 {p}')

    # 4) helm：K8s Service 端口，容器内外同端口（无宿主机映射概念）
    m = re.search(r'MYSQL_PORT:\s*"?(\d+)"?', read(HELM_VALUES))
    if m:
        p = m.group(1)
        ok = p == CONTAINER_PORT
        mark = '  [OK]' if ok else '  [ERR] 期望 ' + CONTAINER_PORT
        print(f'  {p}  helm MYSQL_PORT（K8s Service，容器内外同端口）{mark}')
        if not ok:
            errors.append(f'helm 的 MYSQL_PORT 应为 {CONTAINER_PORT}，实际 {p}')

    # 5) GitLab CI：service 与 job 同网络 → service 名 + 容器端口
    found = re.findall(r'jdbc:mysql://([\w.-]+):(\d+)', read(GITLAB_CI))
    bad = [(h, p) for h, p in found if p != CONTAINER_PORT]
    mark = '  [OK]' if not bad else f'  [ERR] 期望容器端口 {CONTAINER_PORT}'
    print(f'  {found or "?"}  .gitlab-ci.yml service 端口{mark}')
    if bad:
        errors.append(f'.gitlab-ci.yml 里 service 端口应为 {CONTAINER_PORT}，实际 {bad}')

    # 6) GitHub Actions：service 通过端口映射暴露 → job 内用 localhost:映射端口
    gh = read(GITHUB_CI)
    found = re.findall(r'jdbc:mysql://([\w.-]+):(\d+)', gh)
    bad = [(h, p) for h, p in found if p != HOST_PORT]
    mark = '  [OK]' if not bad else f'  [ERR] 期望映射端口 {HOST_PORT}'
    print(f'  {found or "?"}  github ci.yml service 端口{mark}')
    if bad:
        errors.append(f'github ci.yml 里 service 端口应为 {HOST_PORT}，实际 {bad}')

    # 7) GitHub Actions 的 service 端口映射本身
    m = re.search(r"ports:\s*\[?'?(\d+):(\d+)", gh)
    if m:
        host, container = m.group(1), m.group(2)
        ok = (host == HOST_PORT and container == CONTAINER_PORT)
        mark = '  [OK]' if ok else '  [ERR] 期望 ' + HOST_PORT + ':' + CONTAINER_PORT
        print(f'  {host}:{container}  github ci.yml service 映射{mark}')
        if not ok:
            errors.append(f'github ci.yml 的 service 映射应为 {HOST_PORT}:{CONTAINER_PORT}，实际 {host}:{container}')

    print()
    if errors:
        print(f'❌ {len(errors)} 处不一致:')
        for e in errors:
            print('  ', e)
        return 1
    print(f'✅ 全部一致：容器内 {CONTAINER_PORT} / 对外 {HOST_PORT}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
