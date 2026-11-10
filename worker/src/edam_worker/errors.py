"""
worker 的错误分类

**为什么要分类**：处理失败时只有两种正确的处理方式 ——
"再试一次" 和 "别试了，等人处理"。把两者混为一谈，后果都是静默的：

- 什么都重试 ⇒ 配置错误（比如 WORKER_TOKEN 不一致）时白白转码 3 遍，
  CPU 和时间全浪费，最后还是失败；
- 什么都不重试 ⇒ 瞬时抖动（后端正在重启 / 网络闪断）时一次就丢，
  后端的 hls_status 永远停在 pending，用户点播放还是 404。

所以这里把"重试也不会变好"的失败单独拎出来，让调用方能区分。
"""


class PermanentWorkerError(Exception):
    """重试也不会变好的失败 —— 不重试，直接记为 failed_permanent。

    典型场景：回调被后端拒了（401/403）。token 不一致是**配置问题**，
    重转码 3 遍结果完全一样，只是白白消耗 3 倍 CPU 和时间；
    而真正该做的是让日志和指标喊出来，让人去改配置。

    与之相对，瞬时故障（连不上后端、5xx）用普通异常表达 ——
    那些值得把整个任务重跑一遍。
    """


class TaskDeadlineExceeded(PermanentWorkerError):
    """任务总时长超过 WORKER_TASK_DEADLINE_SEC。

    **为什么算"永久失败"、不重试**：超时的原因是这个视频本身就大
    （转码要 hours 级）。同样的输入再跑一遍还是超 ——
    每次重试都是几小时的 CPU 加一遍完整的下载/转码/上传。

    宁可明确失败、让人去调大上限或换台更快的机器，
    也不要一个视频把 worker 的 CPU 占满 hours。

    2026-11-10 事故：RabbitMQ 的未确认投递超时（默认 30 分钟）
    掐断 channel 时，异常以 CancelledError 的形式冒出来 ——
    Python 3.8+ 里 CancelledError 继承 BaseException，
    ``except Exception`` 抓不到它 ⇒ 这次失败在监控上完全看不见。
    加上 worker 自己的任务上限，超时就由 worker 主动触发，
    变成一个**干净、可诊断、可告警**的失败。
    """
