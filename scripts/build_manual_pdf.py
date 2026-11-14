"""把 modify/ 下的使用手册 Markdown 渲染成 doc/ 下的 PDF。

用法：
    python scripts/build_manual_pdf.py
    python scripts/build_manual_pdf.py --only employee

做法：Markdown --pandoc--> HTML --Chrome headless--> PDF。

为什么要写这个脚本：之前的两份手册 PDF 是手工渲染后提交进来的，
**没有留下可复现的构建方式** —— 手册内容改了以后没人知道该怎么重新导出，
结果就是文档与实现漂移（普通员工手册说"可以上传"，而代码早就改成只读了）。

排版说明（与既有 PDF 保持一致的观感）：
- 正文用衬线中文字体（NSimSun / 宋体），标题与强调用 Segoe UI
- A4 纵向，页边距 18mm，页脚带页码
- 首行大标题 + 副标题 + 元信息表作为封面区

依赖：pandoc + Chrome（自动探测常见安装路径）。
"""
import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (Markdown 源, PDF 目标, 标题, 副标题, 适用角色)
#
# 'key' 是 --only 的取值。**不能拿文件名去做匹配** —— 文件名是中文的，
# 拿 'admin' 去 in 文件名永远为假 ⇒ `--only admin` 会一本都不渲染，
# 而且退出码还是 0（看起来"成功"了，实际上什么也没干）。
MANUALS = [
    {
        'key': 'employee',
        'md': 'modify/2026-11-05-普通员工使用手册.md',
        'pdf': 'doc/2026-11-05-普通员工使用手册.pdf',
        'title': 'EDAM 普通员工使用手册',
        'subtitle': 'employee / dept_manager / auditor',
    },
    {
        'key': 'admin',
        'md': 'modify/2026-11-05-管理员使用手册.md',
        'pdf': 'doc/2026-11-05-管理员使用手册.pdf',
        'title': 'EDAM 管理员使用手册',
        'subtitle': 'admin / dept_manager',
    },
]

CHROME_CANDIDATES = [
    r'C:\Program Files\Google\Chrome\Application\chrome.exe',
    r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
    r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
]

CSS = """
@page { size: A4; margin: 18mm 16mm 16mm 16mm; }
body {
  font-family: "NSimSun", "SimSun", "Songti SC", serif;
  font-size: 10.5pt; line-height: 1.75; color: #1f2328; margin: 0;
}
h1, h2, h3, h4, .kicker, .meta, th {
  font-family: "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif;
}
h1 {
  font-size: 22pt; margin: 0 0 6mm 0; padding-bottom: 3mm;
  border-bottom: 2.5px solid #2f6feb; letter-spacing: .5px;
}
h2 {
  font-size: 14pt; margin: 9mm 0 3mm 0; padding-left: 3mm;
  border-left: 4px solid #2f6feb; line-height: 1.4;
}
h3 { font-size: 11.5pt; margin: 6mm 0 2mm 0; color: #24292f; }
h4 { font-size: 10.5pt; margin: 4mm 0 1.5mm 0; color: #57606a; }
p { margin: 2.2mm 0; }
ul, ol { margin: 2mm 0 2mm 0; padding-left: 7mm; }
li { margin: 1mm 0; }
blockquote {
  margin: 3mm 0; padding: 2.5mm 4mm; background: #f6f8fa;
  border-left: 3px solid #d0d7de; color: #57606a; border-radius: 0 3px 3px 0;
}
blockquote p { margin: 1mm 0; }
code {
  font-family: Consolas, "Courier New", monospace; font-size: 9.5pt;
  background: #f0f3f6; padding: .5mm 1.2mm; border-radius: 2px;
}
pre {
  background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 4px;
  padding: 3mm 4mm; overflow-x: hidden; line-height: 1.5;
}
pre code { background: none; padding: 0; font-size: 9pt; }
table {
  border-collapse: collapse; width: 100%; margin: 3mm 0; font-size: 9.5pt;
}
th, td { border: 1px solid #d0d7de; padding: 1.6mm 2.5mm; text-align: left; }
th { background: #f0f3f6; font-weight: 600; }
tr:nth-child(even) td { background: #fafbfc; }
hr { border: none; border-top: 1px solid #d0d7de; margin: 6mm 0; }
strong { color: #0b3d91; }
img { max-width: 100%; }
.cover {
  border-bottom: 2.5px solid #2f6feb; margin-bottom: 8mm; padding-bottom: 5mm;
}
.cover .kicker {
  font-size: 9pt; letter-spacing: 2.5px; text-transform: uppercase;
  color: #2f6feb; margin-bottom: 2mm;
}
.cover h1 { border: none; padding: 0; margin: 0 0 2mm 0; }
.cover .subtitle { color: #57606a; font-size: 11pt; font-family: "Segoe UI", sans-serif; }
.cover .meta {
  color: #8b949e; font-size: 9pt; margin-top: 3mm;
  font-family: "Segoe UI", sans-serif;
}
.note {
  background: #fff8e6; border-left: 4px solid #d4a72c;
  padding: 2.5mm 4mm; margin: 3mm 0; border-radius: 0 3px 3px 0;
}
.note p { margin: 1mm 0; }
"""


def find_chrome():
    for c in CHROME_CANDIDATES:
        if os.path.exists(c):
            return c
    found = shutil.which('chrome') or shutil.which('msedge') or shutil.which('chromium')
    return found


def render_html(md_text, meta):
    """用 pandoc 把 Markdown 转成带封面区的独立 HTML。"""
    # 注意 encoding：Windows 控制台默认 GBK，直接把中文塞进 text=True 的
    # stdin 会抛 UnicodeEncodeError（'⚠' 就不在 GBK 里）。
    r = subprocess.run(
        ['pandoc', '-f', 'markdown+pipe_tables', '-t', 'html5'],
        input=md_text.encode('utf-8'), capture_output=True, check=True,
    )
    body = r.stdout.decode('utf-8')

    # pandoc 会把一级标题留在正文里；封面已经有标题，去掉重复的那行
    body = re.sub(r'^<h1[^>]*>.*?</h1>\s*', '', body, count=1, flags=re.S)

    # 把 markdown 里的 "> ⚠️" 提示块升级成高亮样式
    body = body.replace('<blockquote>', '<blockquote class="note">')

    cover = f"""
<div class="cover">
  <div class="kicker">EDAM v3.4</div>
  <h1>{html.escape(meta['title'])}</h1>
  <div class="subtitle">{html.escape(meta['subtitle'])}</div>
  <div class="meta">企业全格式数字资产防泄密系统 · 版本 v3.4 · 2026-11-05</div>
</div>
"""
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>{html.escape(meta['title'])}</title>
<style>{CSS}</style>
</head><body>
{cover}
{body}
</body></html>
"""


def to_pdf(chrome, html_path, pdf_path):
    """调 Chrome headless 打印 PDF。

    踩过的三个坑（都表现为「返回码 0，但不产出文件」，非常容易误判成成功）：

    1. 必须用 `--headless=new` —— 新版 Chrome 已经移除了旧 headless 实现，
       旧写法**静默什么都不做**。
    2. 必须给一个**全新的 `--user-data-dir`** —— 机器上常驻着交互式 Chrome，
       默认 profile 被锁着时 headless 进程会直接放弃。
    3. 进程返回后 PDF **可能还没落盘** —— 必须轮询等文件出现，
       不能只看返回码。
    """
    tmp_pdf = pdf_path + '.new'
    if os.path.exists(tmp_pdf):
        os.unlink(tmp_pdf)

    profile_dir = tempfile.mkdtemp(prefix='edam_chrome_')
    try:
        cmd = [
            chrome,
            '--headless=new',
            '--disable-gpu',
            '--no-first-run',
            '--no-default-browser-check',
            f'--user-data-dir={profile_dir}',
            '--no-pdf-header-footer',
            f'--print-to-pdf={tmp_pdf}',
            html_path,
        ]
        r = subprocess.run(cmd, capture_output=True, text=True)

        # 轮询等文件落盘（Chrome 是异步写）
        deadline = time.time() + 30
        while not os.path.exists(tmp_pdf) and time.time() < deadline:
            time.sleep(0.3)
    finally:
        # Chrome 可能还锁着 profile 里的缓存文件，删不掉就留着 ——
        # 它在系统临时目录里，下次开机自然被清掉。
        shutil.rmtree(profile_dir, ignore_errors=True)

    if not os.path.exists(tmp_pdf):
        raise RuntimeError(f'PDF 生成失败：{r.stderr[-500:]}')
    os.replace(tmp_pdf, pdf_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only', choices=['employee', 'admin'], help='只渲染其中一本')
    args = ap.parse_args()

    chrome = find_chrome()
    if not chrome:
        print('ERROR: 找不到 Chrome / Edge，无法生成 PDF')
        return 1
    if not shutil.which('pandoc'):
        print('ERROR: 找不到 pandoc')
        return 1

    selected = [m for m in MANUALS
                if not args.only or args.only == m['key']]
    if not selected:
        print(f'ERROR: 没有匹配的手册（--only {args.only}）')
        return 1

    for m in selected:
        md_path = ROOT / m['md']
        pdf_path = ROOT / m['pdf']
        if not md_path.exists():
            print(f'SKIP: {m["md"]} 不存在')
            continue

        html_text = render_html(md_path.read_text(encoding='utf-8'), m)
        with tempfile.NamedTemporaryFile('w', suffix='.html', delete=False,
                                         encoding='utf-8') as f:
            f.write(html_text)
            tmp_html = f.name
        try:
            to_pdf(chrome, tmp_html, str(pdf_path))
            size = pdf_path.stat().st_size
            print(f'OK  {m["pdf"]}  ({size // 1024} KB)')
        finally:
            os.unlink(tmp_html)

    return 0


if __name__ == '__main__':
    sys.exit(main())
