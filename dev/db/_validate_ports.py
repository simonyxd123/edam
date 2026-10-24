"""校验后端端口在各处是否一致。

EDAM 曾经有三方不一致：application.yml 8080 / 前端 vite proxy 8092 /
启动参数再覆盖 8092 —— 改一处忘另一处就是"前端全 502"。

本脚本把端口的所有来源列出来对照，任何不一致直接报出来。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

APP_YML = ROOT / 'backend/src/main/resources/application.yml'
VITE_TS = ROOT / 'web/vite.config.ts'
HELM_VALUES = ROOT / 'helm/edam/values.yaml'
HELM_DEPLOY = ROOT / 'helm/edam/templates/deployment.yaml'
WORKER_CFG = ROOT / 'worker/src/edam_worker/config.py'
PROM_YML = ROOT / 'dev/monitoring/prometheus.yml'
OPENAPI = ROOT / 'doc/openapi.yaml'


def read(p):
    return p.read_text(encoding='utf-8') if p.exists() else ''


def find_port(text, patterns, label, out):
    for pat in patterns:
        m = re.search(pat, text, re.M)
        if m:
            out.append((label, m.group(1)))
            return
    out.append((label, None))


def main():
    ports = []

    find_port(read(APP_YML),
              [r'^server:\s*\n(?:.*\n)*?\s*port:\s*(\d+)'],
              'backend application.yml server.port', ports)

    find_port(read(VITE_TS),
              [r"target:\s*process\.env\.VITE_API_BASE_URL\s*\|\|\s*'http://localhost:(\d+)'"],
              'web vite proxy target', ports)

    find_port(read(HELM_VALUES),
              [r'^backend:\s*$[\s\S]*?^\s{2}service:\s*$[\s\S]*?^\s{4}port:\s*(\d+)\s*$'],
              'helm backend.service.port', ports)

    find_port(read(HELM_VALUES),
              [r'SERVER_PORT:\s*"?(\d+)"?'],
              'helm backend.env.SERVER_PORT', ports)

    find_port(read(HELM_VALUES),
              [r'BACKEND_BASE_URL:\s*http://edam-backend:(\d+)'],
              'helm worker BACKEND_BASE_URL', ports)

    find_port(read(HELM_DEPLOY),
              [r'prometheus\.io/port:\s*"(\d+)"'],
              'helm deployment prometheus.io/port', ports)

    find_port(read(WORKER_CFG),
              [r'BACKEND_BASE_URL[^\n]*?localhost:(\d+)'],
              'worker config BACKEND_BASE_URL default', ports)

    find_port(read(PROM_YML),
              [r"host\.docker\.internal:(\d+)"],
              'dev prometheus scrape target', ports)

    find_port(read(OPENAPI),
              [r'-\s*url:\s*http://localhost:(\d+)/api/v1'],
              'openapi server url', ports)

    print('后端端口来源：')
    for label, p in ports:
        print(f'  {p or "?":>6}  {label}')

    values = {p for _, p in ports if p}
    missing = [l for l, p in ports if not p]

    print()
    if missing:
        print(f'❌ 没能从这些位置读到端口：{missing}')
        return 1
    if len(values) > 1:
        print(f'❌ 端口不一致：{sorted(values)} —— 改一处忘另一处会变成"前端 502"')
        return 1

    print(f'✅ 全部一致：{values.pop()}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
