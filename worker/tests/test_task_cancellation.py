"""
任务被取消 / 超时的回归测试（2026-11-10 事故）。

**事故是什么**：RabbitMQ 的未确认投递超时（consumer_timeout，默认 30 分钟）
到点 ⇒ broker 掐断 channel ⇒ 正在跑的转码协程被连带取消 ⇒
异常以 ``asyncio.CancelledError`` 的形式冒出来。

**为什么这些用例必须有**：

1. ``asyncio.CancelledError`` 在 Python 3.8+ 继承的是 **BaseException**，
   不是 Exception ⇒ 原来的 ``except Exception`` **抓不到它**。
   结果是：一次被 broker 掐死的转码，不打失败日志、不进 Prometheus、
   /health/worker 也不更新 ⇒ **在监控上完全隐形**，
   而视频确实没转出来（用户点播放还是 404）。

2. 取消中的协程**不能 await** ⇒ 被取消时正在跑的 FFmpeg 子进程
   杀不掉 ⇒ 变成孤儿进程继续吃 CPU，而 worker 侧看不到任何异常。

3. worker 必须自己有一个比 broker 更短的任务上限 ——
   否则超时永远由 broker 触发，worker 拿不到那个干净的失败。
"""

import asyncio
import os
import sys

import pytest

from edam_worker.errors import TaskDeadlineExceeded
from edam_worker.main import WorkerApp

pytestmark = pytest.mark.asyncio


class _Resp:
    """最小可用的 requests.Response 替身"""

    def __init__(self, status_code: int, text: str = ""):
        self.status_code = status_code
        self.text = text


# ============================================================================
# 1. CancelledError 必须被显式接住（而不是被 except Exception 漏掉）
# ============================================================================


class TestCancellationIsNotSilent:
    """取消必须留下痕迹"""

    @pytest.mark.asyncio
    async def test_cancelled_error_is_not_swallowed_by_except_exception(self):
        """**核心回归**：处理器收到 CancelledError 时，
        必须 (a) 向上抛（不能吞），(b) 打失败日志，(c) 通知后端 failed。

        (b)(c) 都做不到的话，这次失败在监控上就是隐形的。
        """
        from unittest.mock import patch

        from edam_worker.processors.video import VideoProcessor

        processor = VideoProcessor()

        async def boom_download(*args, **kwargs):
            raise asyncio.CancelledError()

        processor._download_from_minio = boom_download

        notified = {}

        async def fake_notify(video_id, **kwargs):
            notified.update(kwargs)
            notified["video_id"] = video_id

        processor._notify_backend_status = fake_notify

        with pytest.raises(asyncio.CancelledError):
            await processor.process({"video_id": 7, "input_path": "uploads/1/a.mp4"})

        # 后端必须收到"失败"状态 —— 否则 hls_status 永远停在 pending，
        # 用户点播放还是 404，而且前端永远转圈
        assert notified.get("hls_status") == 3, \
            "取消时必须把 hls_status 报成 failed(3)，否则前端永远转圈"
        assert notified.get("fingerprint_status") == 3

    @pytest.mark.asyncio
    async def test_backend_notify_completes_during_cancellation(self):
        """**取消时后端必须真的收到回调** —— 这是最容易漏的一环。

        处理器的 `finally` 里要 await 一次 HTTP 回调；而协程正处在
        "取消中"的状态。如果这个 await 被打断（或者压根没发出去），
        后端就永远不知道这次转码失败了 ⇒ hls_status 停在 pending ⇒
        用户点播放还是 404。

        这里用**真的 task.cancel()**（不是手动 raise CancelledError）——
        手动 raise 测不出"取消中的协程还能不能 await"这个问题，
        而线上恰恰是 task.cancel() 触发的。
        """
        from unittest.mock import patch

        from edam_worker.processors.video import VideoProcessor

        processor = VideoProcessor()

        started = asyncio.Event()

        async def slow_download(*args, **kwargs):
            started.set()
            await asyncio.sleep(60)   # 卡在这里等被取消
            return "/tmp/fake.mp4"

        processor._download_from_minio = slow_download

        sent = []

        def fake_patch(url, json=None, headers=None, timeout=None):
            sent.append(json)
            return _Resp(200, "ok")

        with patch("edam_worker.processors.video.requests.patch", fake_patch):
            task = asyncio.ensure_future(
                processor.process({"video_id": 8, "input_path": "uploads/1/a.mp4"})
            )
            await asyncio.wait_for(started.wait(), timeout=5)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task

        assert sent, "取消时后端回调必须真的发出去（否则 hls_status 永远 pending）"
        assert sent[0].get("hls_status") == 3, \
            "取消 ⇒ 报 failed(3)，前端要能看到'处理失败'而不是永远转圈"

    @pytest.mark.asyncio
    async def test_cancelled_error_propagates_to_retry_wrapper(self):
        """CancelledError 必须一路抛到 _process_with_retry ——
        被吞掉的话，aio_pika 会认为消息处理完了并 ACK 掉，
        而实际上后端什么都没收到。
        """
        app = WorkerApp()

        async def cancelled_processor(payload):
            raise asyncio.CancelledError()

        with pytest.raises(asyncio.CancelledError):
            await app._process_with_retry("video", {"video_id": 9}, cancelled_processor)

    @pytest.mark.asyncio
    async def test_cancellation_is_recorded_as_failure_state(self):
        """被 broker 掐断时，/health/worker 的 last_failure_* 必须更新。

        这是"失败可见"的最后一环 —— 运维 curl /health/worker
        就能看出"最近一次失败是什么"。
        """
        app = WorkerApp()
        # 不是自己关闭 ⇒ 走"broker 掐断"那条路径
        app._shutting_down = False

        async def cancelled_processor(payload):
            raise asyncio.CancelledError()

        with pytest.raises(asyncio.CancelledError):
            await app._process_with_retry("video", {"video_id": 11}, cancelled_processor)

        state = app._last_task_state["video"]
        assert "last_failure_at" in state, \
            "被取消的任务必须记成失败 —— 否则监控上完全看不见"
        assert state["last_failure_video_id"] == 11

    @pytest.mark.asyncio
    async def test_shutdown_cancellation_is_not_recorded_as_failure(self):
        """**反过来**：自己优雅关闭时的取消不算失败。

        运维在滚动重启时会看到一堆"失败"，那是噪音，会把真信号淹掉。
        """
        app = WorkerApp()
        app._shutting_down = True

        async def cancelled_processor(payload):
            raise asyncio.CancelledError()

        with pytest.raises(asyncio.CancelledError):
            await app._process_with_retry("video", {"video_id": 12}, cancelled_processor)

        state = app._last_task_state.get("video", {})
        assert "last_failure_at" not in state, \
            "优雅关闭导致的取消不该记成失败"


# ============================================================================
# 2. worker 自己的任务上限必须先于 broker 触发
# ============================================================================


class TestTaskDeadline:
    """WORKER_TASK_DEADLINE_SEC 的闸门"""

    @pytest.mark.asyncio
    async def test_deadline_exceeded_raises_permanent_error(self, monkeypatch):
        """超时 ⇒ TaskDeadlineExceeded（永久失败，不重试）。

        同样的输入再跑一遍还是超，每次重试都是几小时的 CPU。
        """
        app = WorkerApp()
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_TASK_DEADLINE_SEC", 0.2
        )
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_MAX_ATTEMPTS", 3
        )

        calls = []

        async def slow_processor(payload):
            calls.append(payload)
            await asyncio.sleep(10)
            return None

        with pytest.raises(TaskDeadlineExceeded):
            await app._process_with_retry("video", {"video_id": 21}, slow_processor)

        # 只跑了一次 —— 永久失败不重试
        assert len(calls) == 1, \
            "超时不该重试 —— 同样的输入再跑一遍还是超，纯烧 CPU"

    @pytest.mark.asyncio
    async def test_backend_notified_when_deadline_fires(self, monkeypatch):
        """**任务上限触发时，后端也必须收到 failed 回调**。

        `asyncio.wait_for` 超时 ⇒ 取消内层协程 ⇒ 处理器的
        `except CancelledError` 接住 ⇒ `finally` 里发回调。
        这一条链路必须通 —— 否则用户看到的是"永远处理中"，
        而 worker 那边已经判失败了。
        """
        from unittest.mock import patch

        from edam_worker.processors.video import VideoProcessor

        app = WorkerApp()
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_TASK_DEADLINE_SEC", 0.3
        )

        processor = VideoProcessor()

        async def slow_download(*args, **kwargs):
            await asyncio.sleep(60)
            return "/tmp/fake.mp4"

        processor._download_from_minio = slow_download

        sent = []

        def fake_patch(url, json=None, headers=None, timeout=None):
            sent.append(json)
            return _Resp(200, "ok")

        with patch("edam_worker.processors.video.requests.patch", fake_patch):
            with pytest.raises(TaskDeadlineExceeded):
                await app._process_with_retry(
                    "video",
                    {"video_id": 41, "input_path": "uploads/1/a.mp4"},
                    processor.process,
                )

        assert sent, "任务上限触发时后端也必须收到回调 —— 否则前端永远转圈"
        assert sent[0].get("hls_status") == 3, \
            "任务超时时 hls_status 必须是 failed(3)，不是 pending"

    @pytest.mark.asyncio
    async def test_internal_timeout_error_is_not_mistaken_for_deadline(self, monkeypatch):
        """**内部抛的 TimeoutError 不能被误报成"任务超时"。**

        Python 3.11+ 起 ``asyncio.TimeoutError`` 就是内置的 ``TimeoutError``，
        而 ``socket.timeout`` 也是它 —— MinIO 的一次网络抖动
        就会冒出一个 TimeoutError。

        如果用 ``except asyncio.TimeoutError`` 来判定"是不是我超时了"，
        这种抖动就会被误报成"任务超时"（= 永久失败 = 不重试），
        而它本来是个**该重试**的瞬时故障。

        所以必须按"跑完了没有"判定，而不是按异常类型。
        """
        app = WorkerApp()
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_TASK_DEADLINE_SEC", 30.0
        )
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_MAX_ATTEMPTS", 1
        )

        class _SocketTimeout(TimeoutError):
            """模拟 socket.timeout（3.11+ 它就是内置 TimeoutError）"""

        async def flaky_processor(payload):
            raise _SocketTimeout("connection reset by peer")

        with pytest.raises(_SocketTimeout):
            await app._process_with_retry(
                "video", {"video_id": 51}, flaky_processor
            )

    @pytest.mark.asyncio
    async def test_deadline_budget_is_shared_across_attempts(self, monkeypatch):
        """**整条消息共享一个预算**，不是每次尝试各算一份。

        broker 的未确认投递超时覆盖的是消息从投递到 ack 的**全部**时长。
        如果每次尝试各有一份预算，3 次 × 1 小时 = 3 小时，
        而 broker 只给 30 分钟 ⇒ 第二次尝试照样被掐断。
        """
        app = WorkerApp()
        budget = 0.6
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_TASK_DEADLINE_SEC", budget
        )
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_MAX_ATTEMPTS", 3
        )
        monkeypatch.setattr(
            "edam_worker.main.settings.WORKER_RETRY_BASE_BACKOFF_SEC", 0.0
        )

        calls = []

        async def flaky_processor(payload):
            calls.append(payload)
            # 每次尝试都耗掉一部分预算
            await asyncio.sleep(0.2)
            raise RuntimeError("transient")

        started = asyncio.get_event_loop().time()
        with pytest.raises(Exception):
            await app._process_with_retry("video", {"video_id": 22}, flaky_processor)
        elapsed = asyncio.get_event_loop().time() - started

        # **真正的不变式是总耗时被预算兜住**，而不是尝试次数 ——
        # 次数少只是现象，总时长有界才是要害（broker 只看总时长）。
        assert elapsed <= budget + 0.3, \
            f"总耗时 {elapsed:.2f}s 超出预算 {budget}s —— 预算没有跨尝试共享"
        assert len(calls) <= 3

    @pytest.mark.asyncio
    async def test_timeout_budget_self_check_detects_bad_ordering(self, monkeypatch):
        """启动自检必须能发现"任务上限 >= broker 超时"这种致命配置。"""
        from edam_worker.config import timeout_budget_warnings

        monkeypatch.setattr(
            "edam_worker.config.settings.RABBITMQ_CONSUMER_TIMEOUT_SEC", 1800.0
        )
        monkeypatch.setattr(
            "edam_worker.config.settings.WORKER_TASK_DEADLINE_SEC", 3600.0
        )
        monkeypatch.setattr(
            "edam_worker.config.settings.FFMPEG_TIMEOUT_SEC", 600.0
        )

        warnings = timeout_budget_warnings()
        assert warnings, "任务上限 >= broker 超时必须被自检发现"
        assert any("RABBITMQ_CONSUMER_TIMEOUT_SEC" in w for w in warnings)

    @pytest.mark.asyncio
    async def test_timeout_budget_self_check_passes_on_good_ordering(self, monkeypatch):
        from edam_worker.config import timeout_budget_warnings

        monkeypatch.setattr(
            "edam_worker.config.settings.RABBITMQ_CONSUMER_TIMEOUT_SEC", 14400.0
        )
        monkeypatch.setattr(
            "edam_worker.config.settings.WORKER_TASK_DEADLINE_SEC", 10800.0
        )
        monkeypatch.setattr(
            "edam_worker.config.settings.FFMPEG_TIMEOUT_SEC", 3600.0
        )

        assert timeout_budget_warnings() == []


# ============================================================================
# 3. 被取消时必须杀掉子进程（否则变成吃 CPU 的孤儿）
# ============================================================================


class TestSubprocessKilledOnCancel:
    """取消中的协程不能 await ⇒ 子进程要用"不 await"的杀法"""

    @pytest.mark.asyncio
    async def test_ffmpeg_is_killed_when_transcode_is_cancelled(self):
        """转码被取消时，FFmpeg 子进程必须被杀掉。

        不杀的话它变成孤儿继续吃 CPU —— 而 worker 侧看不到任何异常，
        /health/worker 照样返回 ok。
        """
        from unittest.mock import patch

        from edam_worker.processors.video import VideoProcessor

        processor = VideoProcessor()

        # 造一个"跑着不退出"的假 FFmpeg
        class _FakeProc:
            def __init__(self):
                self.returncode = None
                self.terminated = False
                self.killed = False

            def terminate(self):
                self.terminated = True

            def kill(self):
                self.killed = True

        fake = _FakeProc()

        async def fake_communicate():
            # 一直不返回 —— 只有被取消才会结束
            await asyncio.sleep(3600)
            return b"", b""

        fake.communicate = fake_communicate

        async def create_subprocess_exec(*args, **kwargs):
            return fake

        def fake_write_key_files(*args, **kwargs):
            return None

        processor._write_key_files = fake_write_key_files

        with patch("edam_worker.processors.video.asyncio.create_subprocess_exec",
                   create_subprocess_exec):
            task = asyncio.ensure_future(
                processor._hls_transcode(
                    "/tmp/fake.mp4", 31, "watermark", work_dir="/tmp"
                )
            )
            # 等 FFmpeg 真的起来
            await asyncio.sleep(0.2)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task

        assert fake.terminated or fake.killed, \
            "转码被取消时 FFmpeg 必须被杀 —— 否则它变成吃 CPU 的孤儿进程"

    @pytest.mark.asyncio
    async def test_kill_process_nowait_does_not_require_await(self):
        """kill_process_nowait 能在没有 await 的地方安全调用。"""
        from edam_worker.procutil import kill_process_nowait

        class _FakeProc:
            def __init__(self):
                self.returncode = None
                self.terminated = False

            def terminate(self):
                self.terminated = True

            def kill(self):
                pass

        proc = _FakeProc()
        kill_process_nowait(proc)
        assert proc.terminated, "kill_process_nowait 至少要 terminate 掉进程"
