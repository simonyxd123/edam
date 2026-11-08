"""
子进程超时的回归测试（2026-11-08 审计）。

**为什么要有这个测试**：Worker 里 FFmpeg / ffprobe / LibreOffice 三处子进程调用
原先都是 ``await proc.communicate()`` —— 裸等，没有超时。

一个 pathological 的输入（损坏的视频、畸形 docx、半截网络流）就能让协程
**永远不返回**；而 ``prefetch_count=10`` ⇒ **10 个卡死的任务就把整个 worker 堵死**，
队列不再消费，而且**没有异常、没有日志** —— 进程还"活着"，
``/health/worker`` 照样返回 ok，运维完全看不出来。

这里验证：超时确实会抛 TimeoutError，且**子进程真的被杀掉**
（只 terminate 不够 —— 挂在 I/O 上的进程收不到 SIGTERM，会继续吃 CPU）。
"""

import asyncio
import os
import sys

import pytest

from edam_worker.procutil import kill_process, run_with_timeout

pytestmark = pytest.mark.asyncio

# 一个"会一直睡下去"的命令 —— 模拟卡死的 FFmpeg
SLEEP_CMD = [sys.executable, "-c", "import time; time.sleep(600)"]
# 一个"很快退出"的命令
QUICK_CMD = [sys.executable, "-c", "print('ok')"]


async def test_run_with_timeout_returns_output():
    """正常退出的子进程：拿到 stdout 和退出码。"""
    stdout, _stderr, rc = await run_with_timeout(QUICK_CMD, timeout=10)
    assert rc == 0
    assert stdout is not None and b"ok" in stdout


async def test_run_with_timeout_raises_on_hang():
    """卡死的子进程：超时抛 TimeoutError（而不是永远挂着）。"""
    with pytest.raises(asyncio.TimeoutError):
        await run_with_timeout(SLEEP_CMD, timeout=0.5)


async def test_hanging_process_is_actually_killed():
    """**关键**：超时之后子进程必须被杀掉，不能继续吃 CPU。

    验证方式：跑一个会写 PID 文件的子进程，超时后检查该进程已不存在。
    """
    pid_file = f"/tmp/edam-timeout-test-{os.getpid()}.pid"
    if os.path.exists(pid_file):
        os.unlink(pid_file)

    cmd = [
        sys.executable,
        "-c",
        (
            "import os, sys, time;"
            f"open({pid_file!r}, 'w').write(str(os.getpid()));"
            "sys.stdout.flush();"
            "time.sleep(600)"
        ),
    ]

    with pytest.raises(asyncio.TimeoutError):
        await run_with_timeout(cmd, timeout=1.0)

    # 等一小会儿让操作系统回收
    await asyncio.sleep(0.5)

    assert os.path.exists(pid_file), "子进程应该来得及写 PID 文件"
    with open(pid_file) as f:
        child_pid = int(f.read().strip())

    # 进程必须已经不在了（否则就是"超时了但还在跑"）
    assert not _pid_alive(child_pid), f"子进程 {child_pid} 超时后还活着"

    os.unlink(pid_file)


def _pid_alive(pid: int) -> bool:
    """跨平台的进程存活判断。"""
    try:
        if os.name == "nt":
            # Windows：用 tasklist 查（不依赖 psutil）
            import subprocess

            out = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}"],
                capture_output=True, text=True, timeout=10,
            ).stdout
            return str(pid) in out
        os.kill(pid, 0)
        return True
    except (ProcessLookupError, PermissionError):
        return False
    except Exception:
        return False


async def test_kill_process_is_idempotent():
    """对已经退出的进程调 kill_process 不该抛异常。"""
    proc = await asyncio.create_subprocess_exec(
        *QUICK_CMD, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    await proc.communicate()
    await kill_process(proc)   # 不抛
    assert proc.returncode == 0
