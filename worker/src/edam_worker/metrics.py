"""
Prometheus 指标定义

**为什么单独一个模块**：Prometheus 指标是在**模块导入时**注册进
``prometheus_client`` 的全局 CollectorRegistry 的，而同名指标注册两次
会直接抛 ``DuplicateTimeseries`` —— worker 启动即崩，且错误发生在
import 阶段，看起来像"环境问题"，实际是代码问题。

历史上就是这么炸的（2026-10-29 定位）：``main.py`` 同时存在
``uvicorn.run("edam_worker.main:app")`` 和 ``python -m edam_worker.main``
两种启动方式 —— 后者由 runpy 以 ``__main__`` 的身份执行一遍模块体，
uvicorn 随后再按名字 import 一遍（``edam_worker.main``），
**模块体被执行两次** ⇒ 指标注册两次 ⇒ DuplicateTimeseries。

把指标挪到这里之后，``edam_worker.metrics`` 是普通模块，import 会被
``sys.modules`` 缓存 ⇒ 无论 ``main`` 的模块体被执行几次，
指标都只注册一次，且拿到的是同一组对象。

指标名 / 标签保持不变 —— Prometheus 侧的查询、面板和告警都依赖它们
（``monitoring/grafana/alerts/edam-rules.yaml`` 里的
``worker_tasks_processed_total``）。
"""
from prometheus_client import Counter, Histogram

TASKS_PROCESSED = Counter(
    "worker_tasks_processed_total",
    "Total tasks processed (final outcome only, after retries)",
    ["task_type", "result"],   # result ∈ {success, failed_permanent}
)
TASK_ATTEMPTS = Counter(
    "worker_task_attempts_total",
    "Total individual task processing attempts (counts retries)",
    ["task_type", "attempt_result"],   # attempt_result ∈ {success, failed}
)
TASK_DURATION = Histogram(
    "worker_task_duration_seconds",
    "Task processing duration (single attempt; not summed over retries)",
    ["task_type"],
)
