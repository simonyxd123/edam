import io, sys, re, yaml
from pathlib import Path
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# 1. 扫描 Controller
controllers_dir = Path('backend/src/main/java/com/example/edam/controller')
implemented = {}  # {(method, path): class_name}

for java_file in sorted(controllers_dir.glob('*Controller.java')):
    text = java_file.read_text(encoding='utf-8')
    class_mapping = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]+)"', text)
    class_path = (class_mapping.group(1) if class_mapping else '').rstrip('/')
    cls = java_file.stem
    # 匹配带路径字符串的注解
    pattern_with_path = re.compile(
        r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\s*\(\s*(?:value\s*=\s*)?"([^"]+)"'
    )
    # 匹配裸注解（无参数，映射到类路径）
    pattern_bare = re.compile(
        r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\s*\(\s*\)'
    )

    for m in pattern_with_path.finditer(text):
        method = m.group(1).replace('Mapping', '').upper()
        sub = m.group(2)
        if sub.startswith('/'):
            full = class_path + sub
        else:
            full = class_path + '/' + sub
        full = re.sub(r'//+', '/', full).rstrip('/') or '/'
        implemented[(method, full)] = cls

    for m in pattern_bare.finditer(text):
        method = m.group(1).replace('Mapping', '').upper()
        full = class_path or '/'
        full = re.sub(r'//+', '/', full).rstrip('/') or '/'
        implemented[(method, full)] = cls

    # 匹配裸注解（无括号，无参数；Java 允许 @GetMapping 后跟换行 + 其他注解 + public）
    pattern_bare_no_paren = re.compile(
        r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)(?:\s*\n|\s+)(?:@\w+(?:\([^)]*\))?\s*\n)*\s*public\s+'
    )
    for m in pattern_bare_no_paren.finditer(text):
        method = m.group(1).replace('Mapping', '').upper()
        full = class_path or '/'
        full = re.sub(r'//+', '/', full).rstrip('/') or '/'
        # 仅在未添加过时添加（避免覆盖带路径的版本）
        if (method, full) not in implemented:
            implemented[(method, full)] = cls

# 2. 解析 OpenAPI
with open('doc/openapi.yaml', 'r', encoding='utf-8') as f:
    spec = yaml.safe_load(f)

openapi_list = []
for path, methods in spec.get('paths', {}).items():
    for method in ('get', 'post', 'put', 'delete', 'patch'):
        if method in methods:
            op = methods[method]
            tags = op.get('tags', ['untagged'])
            openapi_list.append({
                'method': method.upper(),
                'path': path,
                'tag': tags[0] if tags else 'untagged',
                'summary': op.get('summary', ''),
            })

# 3. 对账
covered = []
missing = []
for e in openapi_list:
    key = (e['method'], e['path'])
    if key in implemented:
        covered.append({**e, 'impl': implemented[key]})
    else:
        missing.append(e)

extra = []
for k, cls in implemented.items():
    found = any(e['method'] == k[0] and e['path'] == k[1] for e in openapi_list)
    if not found:
        extra.append({'method': k[0], 'path': k[1], 'class': cls})

# 4. 输出统计
total_openapi = len(openapi_list)
total_impl = len(implemented)
total_covered = len(covered)
total_missing = len(missing)
total_extra = len(extra)

coverage_pct = total_covered / total_openapi * 100 if total_openapi else 0

print('=' * 70)
print('Controller 端点 vs OpenAPI 端点 对账报告')
print('=' * 70)
print(f'OpenAPI 端点总数:        {total_openapi}')
print(f'Controller 实现端点数:    {total_impl}')
print(f'已覆盖（OpenAPI ↔ 实现）: {total_covered}  ({coverage_pct:.1f}%)')
print(f'未实现（OpenAPI 缺失）:   {total_missing}')
print(f'多余（实现但 OpenAPI 无）:{total_extra}')
print()

# 5. 按 tag 输出缺失
from collections import defaultdict
missing_by_tag = defaultdict(list)
for m in missing:
    missing_by_tag[m['tag']].append(m)

print('--- 未实现端点（按 tag 分组）---')
for tag in sorted(missing_by_tag.keys()):
    items = missing_by_tag[tag]
    print(f'\n[{tag}] ({len(items)} 缺失)')
    for m in items:
        print(f'  {m["method"]:7s} {m["path"]:50s} {m["summary"]}')

if extra:
    print('\n--- 多余端点（实现但 OpenAPI 无）---')
    for e in extra:
        print(f'  {e["method"]:7s} {e["path"]:50s} (in {e["class"]})')

# 6. 写入 markdown 报告
report = []
report.append('# 后端 Controller 端点覆盖报告\n')
report.append(f'- 生成日期：2026-08-12\n')
report.append(f'- 对账对象：`backend/src/main/java/com/example/edam/controller/`（9 个 Controller）vs `doc/openapi.yaml`\n')
report.append(f'- 扫描方式：正则匹配 `@RestController` / `@RequestMapping` / `@*Mapping` 注解\n\n')
report.append('---\n\n')
report.append('## 一、总览\n\n')
report.append('| 指标 | 数值 |\n')
report.append('| --- | --- |\n')
report.append(f'| OpenAPI 端点总数 | {total_openapi} |\n')
report.append(f'| Controller 实现端点数 | {total_impl} |\n')
report.append(f'| 已覆盖 | {total_covered} ({coverage_pct:.1f}%) |\n')
report.append(f'| ⚠️ 未实现（OpenAPI 缺失） | **{total_missing}** |\n')
report.append(f'| 多余（实现但 OpenAPI 无） | {total_extra} |\n\n')

if total_missing == 0:
    report.append('**覆盖率 100%** — 所有 OpenAPI 端点均有 Controller 实现。\n\n')
else:
    report.append(f'**覆盖率 {coverage_pct:.1f}%** — 还有 {total_missing} 个 OpenAPI 端点未实现 Controller。\n\n')

# 按 tag 分组
report.append('## 二、未实现端点（按 tag 分组）\n\n')
if missing:
    for tag in sorted(missing_by_tag.keys()):
        items = missing_by_tag[tag]
        report.append(f'### {tag}（{len(items)} 缺失）\n\n')
        report.append('| 方法 | 路径 | 说明 |\n')
        report.append('| --- | --- | --- |\n')
        for m in items:
            report.append(f'| `{m["method"]}` | `{m["path"]}` | {m["summary"]} |\n')
        report.append('\n')
else:
    report.append('无缺失。\n\n')

# 多余端点
report.append('## 三、多余端点（实现但 OpenAPI 未定义）\n\n')
if extra:
    report.append('| 方法 | 路径 | 实现位置 |\n')
    report.append('| --- | --- | --- |\n')
    for e in extra:
        report.append(f'| `{e["method"]}` | `{e["path"]}` | `{e["class"]}.java` |\n')
    report.append('\n')
else:
    report.append('无多余。\n\n')

# 已覆盖清单
report.append('## 四、已覆盖端点清单\n\n')
report.append(f'共 {total_covered} 个端点，详见 `controller-coverage-covered.json` 备份文件。\n\n')

# 优先级建议
report.append('## 五、优先级建议\n\n')
if missing:
    high = ['auth', 'health', 'videos', 'documents', 'playback']
    p0_tags = [t for t in missing_by_tag.keys() if t in high]
    p1_tags = [t for t in missing_by_tag.keys() if t not in high and t not in ['admin', 'webhooks', 'search', 'tags', 'notifications', 'preview']]
    p2_tags = [t for t in missing_by_tag.keys() if t in ['admin', 'webhooks', 'search', 'tags', 'notifications', 'preview']]

    if p0_tags:
        report.append('### 🔴 P0（影响核心业务流程）\n\n')
        for t in p0_tags:
            report.append(f'- **{t}**：{len(missing_by_tag[t])} 个端点\n')
        report.append('\n')
    if p1_tags:
        report.append('### 🟡 P1（影响实施完整性）\n\n')
        for t in p1_tags:
            report.append(f'- **{t}**：{len(missing_by_tag[t])} 个端点\n')
        report.append('\n')
    if p2_tags:
        report.append('### 🟢 P2（管理后台/扩展功能）\n\n')
        for t in p2_tags:
            report.append(f'- **{t}**：{len(missing_by_tag[t])} 个端点\n')
        report.append('\n')

# 工作量
report.append('## 六、预估工作量\n\n')
report.append(f'- 后端 Controller 实现：{total_missing} 个端点 × 平均 0.5 人天 ≈ **{total_missing * 0.5:.0f} 人天**\n')
report.append(f'- 含单元测试 + 集成测试：约 **{total_missing * 1:.0f} 人天**\n')
report.append(f'- 按 1 个工程师独立开发：约 **{total_missing / 5:.1f} 周**（每周 5 端点）\n')
report.append(f'- 按 2 个工程师并行：约 **{total_missing * 1 / 10:.1f} 周**\n\n')

report.append('## 七、v3.2 路线图建议\n\n')
report.append('1. **优先级 1**：先补全 `auth`/`health`/`videos`/`documents`/`playback` 这 5 个核心 tag\n')
report.append('2. **优先级 2**：补全 `permissions`/`distribution`/`audit`/`users`/`watermarks` 5 个常用 tag\n')
report.append('3. **优先级 3**：扩展 `tags`/`notifications`/`webhooks`/`search`/`preview`/`admin` 6 个扩展 tag\n')
report.append('4. **统一规范**：每个 Controller 必须 @Tag 一致；Swagger 注解与 OpenAPI tag 一一对应\n\n')

report.append('---\n\n')
report.append('**报告结束。**\n')

Path('modify/controller-coverage.md').write_text(''.join(report), encoding='utf-8')
print(f'\n报告已写入 modify/controller-coverage.md')

# 同时备份已覆盖清单到 JSON
import json
Path('modify/controller-coverage-covered.json').write_text(
    json.dumps(covered, ensure_ascii=False, indent=2),
    encoding='utf-8'
)
print(f'已覆盖清单已写入 modify/controller-coverage-covered.json')