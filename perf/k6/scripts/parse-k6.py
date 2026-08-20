#!/usr/bin/env python3
"""
k6 压测结果解析器（v3.4 V4-03）

功能：
- 读取 perf/k6/results/*.json
- 提取关键指标（请求数 / RPS / P50 / P95 / P99 / 失败率 / 错误率 / VU）
- 与 SLO 阈值对比，达标绿/未达标红
- 生成单个 HTML 报告

用法：
    python3 parse-k6.py <results_dir> <output_html>
    python3 parse-k6.py ../results ../results/report.html
"""

import json
import sys
from pathlib import Path
from datetime import datetime


# SLO 阈值（与 modify/2026-08-28-项目最终总结v1.0-v3.3.md 第六节一致）
SLO = {
    "smoke":  {"p50": 200, "p95": 400, "p99": 800, "fail_rate": 0.02},
    "load":   {"p50": 100, "p95": 200, "p99": 500, "fail_rate": 0.01},
    "peak":   {"p50": 150, "p95": 300, "p99": 800, "fail_rate": 0.02},
    "stress": {"p50": None, "p95": None, "p99": None, "fail_rate": None},  # 压测不设阈值
}


def extract_metrics(json_path: Path) -> dict:
    """从单个 k6 JSON 提取关键指标"""
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    metrics = data.get("metrics", {})
    req_dur = metrics.get("http_req_duration", {}).get("values", {})
    http_reqs = metrics.get("http_reqs", {}).get("values", {})
    http_failed = metrics.get("http_req_failed", {}).get("values", {})
    errors = metrics.get("errors", {}).get("values", {})
    vus = metrics.get("vus", {}).get("values", {})

    return {
        "file": json_path.name,
        "stage": json_path.stem.split("-")[0],  # smoke / load / peak / stress
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "total_requests": http_reqs.get("count", 0),
        "avg_rps": round(http_reqs.get("rate", 0), 2),
        "p50_ms": round(req_dur.get("p(50)", 0), 1),
        "p95_ms": round(req_dur.get("p(95)", 0), 1),
        "p99_ms": round(req_dur.get("p(99)", 0), 1),
        "max_ms": round(req_dur.get("max", 0), 1),
        "fail_rate": round(http_failed.get("rate", 0) * 100, 2),
        "error_rate": round(errors.get("rate", 0) * 100, 2),
        "max_vus": vus.get("max", "N/A"),
    }


def check_slo(metrics: dict) -> dict:
    """对照 SLO 阈值，返回达标情况"""
    stage = metrics["stage"]
    thresholds = SLO.get(stage, {})
    if not thresholds or thresholds["p99"] is None:
        return {"checked": False, "results": {}}

    results = {}
    if thresholds["p50"] is not None:
        results["P50"] = {
            "value": metrics["p50_ms"],
            "threshold": thresholds["p50"],
            "pass": metrics["p50_ms"] <= thresholds["p50"],
        }
    if thresholds["p95"] is not None:
        results["P95"] = {
            "value": metrics["p95_ms"],
            "threshold": thresholds["p95"],
            "pass": metrics["p95_ms"] <= thresholds["p95"],
        }
    if thresholds["p99"] is not None:
        results["P99"] = {
            "value": metrics["p99_ms"],
            "threshold": thresholds["p99"],
            "pass": metrics["p99_ms"] <= thresholds["p99"],
        }
    if thresholds["fail_rate"] is not None:
        results["失败率"] = {
            "value": metrics["fail_rate"],
            "threshold": thresholds["fail_rate"] * 100,
            "pass": metrics["fail_rate"] <= thresholds["fail_rate"] * 100,
        }

    return {"checked": True, "results": results}


def render_html(all_metrics: list) -> str:
    """渲染 HTML 报告"""
    css = """
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 20px; background: #f5f5f5; }
    h1 { color: #2c3e50; }
    .stage-card { background: white; border-radius: 8px; padding: 20px; margin: 15px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
    .stage-title { font-size: 1.3em; font-weight: bold; margin-bottom: 12px; color: #34495e; }
    table { border-collapse: collapse; width: 100%; margin-top: 10px; }
    th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
    th { background-color: #34495e; color: white; }
    tr:nth-child(even) { background-color: #f2f2f2; }
    .pass { background-color: #27ae60; color: white; padding: 2px 8px; border-radius: 3px; }
    .fail { background-color: #e74c3c; color: white; padding: 2px 8px; border-radius: 3px; }
    .skip { background-color: #95a5a6; color: white; padding: 2px 8px; border-radius: 3px; }
    .metric { font-weight: bold; }
    .summary { background: #ecf0f1; padding: 15px; border-radius: 6px; margin: 10px 0; }
    """

    html_parts = [f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>EDAM k6 压测报告 - {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}</title>
<style>{css}</style>
</head>
<body>
<h1>EDAM k6 压测报告（v3.4 V4-03）</h1>
<p>生成时间：{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}</p>
<p>SLO 阈值（来自总结报告第六节）：P50<100ms / P95<200ms / P99<500ms / 错误率<1%（负载档）</p>
"""]

    for m in all_metrics:
        slo = check_slo(m)
        html_parts.append(f'<div class="stage-card">')
        html_parts.append(f'<div class="stage-title">{m["stage"].upper()} - {m["file"]}</div>')
        html_parts.append('<table>')
        html_parts.append('<tr><th>指标</th><th>实测值</th></tr>')
        html_parts.append(f'<tr><td>总请求数</td><td class="metric">{m["total_requests"]}</td></tr>')
        html_parts.append(f'<tr><td>平均 RPS</td><td class="metric">{m["avg_rps"]}</td></tr>')
        html_parts.append(f'<tr><td>最大并发 VU</td><td class="metric">{m["max_vus"]}</td></tr>')
        html_parts.append(f'<tr><td>P50 延迟</td><td class="metric">{m["p50_ms"]} ms</td></tr>')
        html_parts.append(f'<tr><td>P95 延迟</td><td class="metric">{m["p95_ms"]} ms</td></tr>')
        html_parts.append(f'<tr><td>P99 延迟</td><td class="metric">{m["p99_ms"]} ms</td></tr>')
        html_parts.append(f'<tr><td>最大延迟</td><td>{m["max_ms"]} ms</td></tr>')
        html_parts.append(f'<tr><td>HTTP 失败率</td><td>{m["fail_rate"]} %</td></tr>')
        html_parts.append(f'<tr><td>业务错误率</td><td>{m["error_rate"]} %</td></tr>')
        html_parts.append('</table>')

        if slo["checked"]:
            html_parts.append('<h3>SLO 检查</h3>')
            html_parts.append('<table>')
            html_parts.append('<tr><th>指标</th><th>实测值</th><th>阈值</th><th>结果</th></tr>')
            for name, r in slo["results"].items():
                status = '<span class="pass">✓ 通过</span>' if r["pass"] else '<span class="fail">✗ 未达标</span>'
                html_parts.append(
                    f'<tr><td>{name}</td><td>{r["value"]}</td>'
                    f'<td>{r["threshold"]}</td><td>{status}</td></tr>'
                )
            html_parts.append('</table>')
        else:
            html_parts.append('<p><span class="skip">⊘ 此档位不检查 SLO（压测档）</span></p>')

        html_parts.append('</div>')

    # 汇总
    html_parts.append('<div class="summary">')
    html_parts.append('<h2>汇总</h2>')
    html_parts.append('<table>')
    html_parts.append('<tr><th>档位</th><th>总请求数</th><th>平均 RPS</th><th>P50 ms</th><th>P95 ms</th><th>P99 ms</th><th>失败率 %</th></tr>')
    for m in all_metrics:
        html_parts.append(
            f'<tr><td>{m["stage"]}</td>'
            f'<td>{m["total_requests"]}</td>'
            f'<td>{m["avg_rps"]}</td>'
            f'<td>{m["p50_ms"]}</td>'
            f'<td>{m["p95_ms"]}</td>'
            f'<td>{m["p99_ms"]}</td>'
            f'<td>{m["fail_rate"]}</td></tr>'
        )
    html_parts.append('</table></div>')

    html_parts.append('</body></html>')
    return "\n".join(html_parts)


def main():
    if len(sys.argv) < 3:
        print("用法: python3 parse-k6.py <results_dir> <output_html>")
        sys.exit(1)

    results_dir = Path(sys.argv[1])
    output_html = Path(sys.argv[2])

    if not results_dir.exists():
        print(f"错误: 目录不存在 {results_dir}")
        sys.exit(1)

    json_files = sorted(results_dir.glob("*-*.json"))
    if not json_files:
        print(f"警告: {results_dir} 下未找到 *-*.json 文件")
        sys.exit(1)

    all_metrics = [extract_metrics(jf) for jf in json_files]
    html = render_html(all_metrics)

    output_html.parent.mkdir(parents=True, exist_ok=True)
    with open(output_html, "w", encoding="utf-8") as f:
        f.write(html)

    print(f"✓ 报告生成：{output_html}")
    print(f"  共处理 {len(all_metrics)} 个 JSON 文件")
    for m in all_metrics:
        slo = check_slo(m)
        if slo["checked"]:
            failed = [k for k, r in slo["results"].items() if not r["pass"]]
            status = "✓ 全达标" if not failed else f"✗ 未达标: {', '.join(failed)}"
            print(f"  - {m['stage']:6s} {status}")


if __name__ == "__main__":
    main()