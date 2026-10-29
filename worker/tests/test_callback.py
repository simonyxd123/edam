"""
回调后端的失败处理测试

**为什么测这个**：2026-10-29 线上抓到的两个坑都在这里 ——
1. 回调失败只 log 不抛 ⇒ 任务被记成"成功"、消息被 ACK，
   Prometheus 显示 100% 成功率，而后端压根没收到状态
   ⇒ hls_status 永远停在 pending ⇒ 用户点播放还是 404；
2. 回调在 `finally` 里抛异常 ⇒ **顶替**了原始的转码异常，
   排障时看到的是回调错误，真正的转码错误被吞了。

这两类都是"静默失败"，没有测试就只能靠用户点播放才发现。
"""
import asyncio
from unittest.mock import patch

import pytest
import requests

from edam_worker.errors import PermanentWorkerError
from edam_worker.processors.video import VideoProcessor


class _Resp:
    """最小可用的 requests.Response 替身"""

    def __init__(self, status_code: int, text: str = ""):
        self.status_code = status_code
        self.text = text


def _make_processor() -> VideoProcessor:
    """构造一个不连 MinIO 的 VideoProcessor（__init__ 只建客户端，不发请求）。"""
    return VideoProcessor()


class TestCallbackFailureHandling:
    """_notify_backend_status 的失败语义"""

    @pytest.fixture(autouse=True)
    def _fast_backoff(self, monkeypatch):
        """退避改成 0，测试别真等。"""
        monkeypatch.setattr(
            "edam_worker.processors.video.settings.WORKER_CALLBACK_RETRY_BACKOFF_SEC", 0.0
        )
        monkeypatch.setattr(
            "edam_worker.processors.video.settings.WORKER_CALLBACK_MAX_ATTEMPTS", 3
        )

    @pytest.mark.asyncio
    async def test_401_raises_permanent_error_without_retrying(self):
        """401 是配置问题 —— 立刻永久失败，不做无谓的重试。"""
        processor = _make_processor()
        with patch("edam_worker.processors.video.requests.patch",
                   return_value=_Resp(401, "unauthorized")) as m:
            with pytest.raises(PermanentWorkerError) as exc:
                await processor._notify_backend_status(1, hls_status=2)
        assert "401" in str(exc.value)
        assert m.call_count == 1, "401 不该重试 —— 重试改变不了 token 不一致"

    @pytest.mark.asyncio
    async def test_403_raises_permanent_error(self):
        processor = _make_processor()
        with patch("edam_worker.processors.video.requests.patch",
                   return_value=_Resp(403, "forbidden")):
            with pytest.raises(PermanentWorkerError):
                await processor._notify_backend_status(1, hls_status=2)

    @pytest.mark.asyncio
    async def test_5xx_is_retried_then_raises(self):
        """5xx 是瞬时故障 —— 在回调层便宜地重试，重试用尽后冒泡。"""
        processor = _make_processor()
        with patch("edam_worker.processors.video.requests.patch",
                   return_value=_Resp(500, "boom")) as m:
            with pytest.raises(Exception) as exc:
                await processor._notify_backend_status(1, hls_status=2)
        assert not isinstance(exc.value, PermanentWorkerError), \
            "5xx 重试用尽后应当按瞬时故障处理（让外层重跑整个任务）"
        assert m.call_count == 3, "5xx 应该重试到 WORKER_CALLBACK_MAX_ATTEMPTS 次"

    @pytest.mark.asyncio
    async def test_transient_failure_then_success(self):
        """第一次连不上、第二次成功 —— 不该抛。"""
        processor = _make_processor()
        with patch("edam_worker.processors.video.requests.patch",
                   side_effect=[requests.ConnectionError("refused"), _Resp(200, "ok")]) as m:
            await processor._notify_backend_status(1, hls_status=2)
        assert m.call_count == 2

    @pytest.mark.asyncio
    async def test_success_sends_worker_token_header(self):
        """回调必须带 X-Worker-Token —— 后端是 fail-closed 的。"""
        processor = _make_processor()
        with patch("edam_worker.processors.video.settings.WORKER_TOKEN", "s3cr3t"):
            with patch("edam_worker.processors.video.requests.patch",
                       return_value=_Resp(200, "ok")) as m:
                await processor._notify_backend_status(1, hls_status=2)
        headers = m.call_args.kwargs["headers"]
        assert headers.get("X-Worker-Token") == "s3cr3t"

    @pytest.mark.asyncio
    async def test_missing_token_is_logged_but_still_attempted(self):
        """没配 token 时也要发出去 —— 让后端明确回 401，而不是静默跳过。"""
        processor = _make_processor()
        with patch("edam_worker.processors.video.settings.WORKER_TOKEN", ""):
            with patch("edam_worker.processors.video.requests.patch",
                       return_value=_Resp(401, "unauthorized")) as m:
                with pytest.raises(PermanentWorkerError):
                    await processor._notify_backend_status(1, hls_status=2)
        assert "X-Worker-Token" not in m.call_args.kwargs["headers"]


class TestCallbackDoesNotMaskOriginalError:
    """回调在 finally 里 —— 绝不能顶替掉原始的转码异常"""

    @pytest.mark.asyncio
    async def test_original_error_survives_callback_failure(self, monkeypatch):
        """转码失败 + 回调失败 ⇒ 抛出来的必须是**转码**那个错。"""
        processor = _make_processor()

        async def boom_download(*args, **kwargs):
            raise RuntimeError("minio_download_failed")

        monkeypatch.setattr(processor, "_download_from_minio", boom_download)

        with patch("edam_worker.processors.video.requests.patch",
                   return_value=_Resp(401, "unauthorized")):
            with pytest.raises(RuntimeError) as exc:
                await processor.process({"video_id": 1, "input_path": "uploads/1/a.mp4"})

        assert "minio_download_failed" in str(exc.value), \
            "原始异常被回调异常顶替了 —— 排障会被带偏"

    @pytest.mark.asyncio
    async def test_callback_failure_alone_fails_the_task(self, monkeypatch):
        """转码成功、回调失败 ⇒ 整个任务必须失败（不能记成成功）。"""
        processor = _make_processor()
        work_dir = None

        async def fake_download(*args, **kwargs):
            nonlocal work_dir
            work_dir = args[2] if len(args) > 2 else kwargs.get("work_dir")
            return "/tmp/fake.mp4"

        async def fake_transcode(*args, **kwargs):
            return ("/tmp/hls", b"0" * 16)

        async def fake_fingerprints(*args, **kwargs):
            return "/tmp/fp.json"

        async def fake_upload_hls(*args, **kwargs):
            return None

        async def fake_upload_fp(*args, **kwargs):
            return None

        async def fake_upload_key(*args, **kwargs):
            return None

        async def fake_probe(*args, **kwargs):
            return 10

        monkeypatch.setattr(processor, "_download_from_minio", fake_download)
        monkeypatch.setattr(processor, "_hls_transcode", fake_transcode)
        monkeypatch.setattr(processor, "_extract_fingerprints", fake_fingerprints)
        monkeypatch.setattr(processor, "_upload_hls", fake_upload_hls)
        monkeypatch.setattr(processor, "_upload_fingerprint", fake_upload_fp)
        monkeypatch.setattr(processor, "_upload_key", fake_upload_key)
        monkeypatch.setattr(processor, "_probe_duration", fake_probe)

        with patch("edam_worker.processors.video.requests.patch",
                   return_value=_Resp(401, "unauthorized")):
            with pytest.raises(PermanentWorkerError):
                await processor.process({"video_id": 1, "input_path": "uploads/1/a.mp4"})
