"""
子进程调用的公共封装：**超时 + 超时后杀掉进程**。

**为什么必须有**（2026-11-08 审计发现）：

Worker 里 FFmpeg / ffprobe / LibreOffice 三处子进程调用原先都是
``await proc.communicate()`` —— **裸等，没有超时**。

一个 pathological 的输入就能让协程**永远不返回**：

- 损坏 / 特制的视频文件让 FFmpeg 卡死
- 损坏的 docx 让 LibreOffice headless 挂住（它本来就爱挂）
- 网络流上的半截文件让 ffprobe 读不完

而 worker 的 ``prefetch_count=10`` ⇒ **10 个卡死的任务就把整个 worker 堵死**：
队列不再消费、CPU 被占满、**没有任何异常、没有任何日志**
（进程还"活着"，``/health/worker`` 照样返回 ok —— 运维看不出来）。

所以：**每次子进程调用都必须带超时，超时后必须杀掉进程**
（只 ``terminate()`` 不够 —— 挂在 I/O 上的进程收不到 SIGTERM，
会继续吃 CPU；给 5 秒优雅退出窗口，不行就 ``kill()``）。
"""

import asyncio
from typing import Optional, Tuple

import structlog

log = structlog.get_logger()

# 超时后给进程的优雅退出窗口（秒）
KILL_GRACE_SEC = 5.0


async def kill_process(proc) -> None:
    """杀掉子进程（先 terminate，宽限期后 kill）。进程已退出则什么都不做。"""
    try:
        if proc.returncode is not None:
            return
        proc.terminate()
        try:
            await asyncio.wait_for(proc.wait(), timeout=KILL_GRACE_SEC)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
    except ProcessLookupError:
        pass
    except Exception as e:  # pragma: no cover - 防御性
        log.warning("process_kill_failed", error=str(e))


async def run_with_timeout(
    cmd,
    timeout: float,
    cwd: Optional[str] = None,
) -> Tuple[Optional[bytes], Optional[bytes], Optional[int]]:
    """跑一个子进程，带超时。

    :return: (stdout, stderr, returncode)
        成功时 returncode 是进程退出码；
        **超时**时 returncode 是 ``None``，且进程已被杀掉。

    :raises asyncio.TimeoutError: 超时（调用方要区别对待"失败"和"卡死"）
    """
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=cwd,
    )
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        await kill_process(proc)
        raise
    return stdout, stderr, proc.returncode
