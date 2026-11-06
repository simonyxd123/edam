"""
文档处理的回调与 URL 拼接测试。

**为什么测这个**：

1. 回调 URL 曾经拼成 `http://edam-backend:8092/api/v1/api/v1/videos/1/status`
   —— `BACKEND_BASE_URL` 在 helm / ops 的 env 里带了 context-path，
   代码又习惯性地拼一次 `/api/v1` ⇒ 404 ⇒ 被当成"其它 4xx" ⇒
   `PermanentWorkerError` ⇒ **视频状态永远不回写**，
   转码明明完成了，`hls_status` 却一直停在 pending。

2. 文档处理此前**根本没有回调** —— Worker 处理完不回写，
   `watermark_status` / `preview_status` 永远停在 pending，
   前端的状态标签恒为 pending。

3. 状态码搞反过一次（2=ready / 3=failed，写成了 3=success / 2=failed）——
   一旦写反，前端会把"失败"显示成"就绪"。
"""
import asyncio
from unittest.mock import patch

import pytest
import requests

from edam_worker.config import backend_api_url
from edam_worker.errors import PermanentWorkerError
from edam_worker.processors.document import DocumentProcessor


class _Resp:
    """最小可用的 requests.Response 替身"""

    def __init__(self, status_code: int, text: str = ""):
        self.status_code = status_code
        self.text = text


# ======================================================================
# URL 拼接
# ======================================================================


class TestBackendApiUrl:
    """BACKEND_BASE_URL 带不带 context-path，拼出来的都要对"""

    def test_base_without_context_path(self, monkeypatch):
        monkeypatch.setattr(
            "edam_worker.config.settings.BACKEND_BASE_URL", "http://localhost:8092"
        )
        assert backend_api_url("videos/1/status") == \
            "http://localhost:8092/api/v1/videos/1/status"

    def test_base_with_context_path(self, monkeypatch):
        """helm / ops 的 env 就是这种写法 —— 不能拼出 /api/v1/api/v1"""
        monkeypatch.setattr(
            "edam_worker.config.settings.BACKEND_BASE_URL",
            "http://edam-backend:8092/api/v1",
        )
        assert backend_api_url("videos/1/status") == \
            "http://edam-backend:8092/api/v1/videos/1/status"

    def test_base_with_trailing_slash(self, monkeypatch):
        monkeypatch.setattr(
            "edam_worker.config.settings.BACKEND_BASE_URL",
            "http://edam-backend:8092/api/v1/",
        )
        assert backend_api_url("documents/2/status") == \
            "http://edam-backend:8092/api/v1/documents/2/status"

    def test_leading_slash_in_path_is_tolerated(self, monkeypatch):
        monkeypatch.setattr(
            "edam_worker.config.settings.BACKEND_BASE_URL", "http://localhost:8092"
        )
        assert backend_api_url("/documents/2/status") == \
            "http://localhost:8092/api/v1/documents/2/status"


# ======================================================================
# 文档回调
# ======================================================================


class TestDocumentCallback:
    """DocumentProcessor._notify_backend_status 的语义"""

    @pytest.fixture(autouse=True)
    def _fast_backoff(self, monkeypatch):
        monkeypatch.setattr(
            "edam_worker.processors.document.settings.WORKER_CALLBACK_RETRY_BACKOFF_SEC", 0.0
        )
        monkeypatch.setattr(
            "edam_worker.processors.document.settings.WORKER_CALLBACK_MAX_ATTEMPTS", 3
        )

    @pytest.mark.asyncio
    async def test_success_reports_ready_statuses(self):
        """成功时两个状态都应是 ready(2)，并带上产物路径"""
        processor = DocumentProcessor()
        with patch("edam_worker.processors.document.requests.patch",
                   return_value=_Resp(200, "ok")) as m:
            await processor._notify_backend_status(
                7, 2, 2,
                "documents/7/watermarkedpdf",
                "documents/7/preview.pdf",
            )
        payload = m.call_args.kwargs["json"]
        assert payload["watermark_status"] == 2
        assert payload["preview_status"] == 2
        assert payload["watermark_path"] == "documents/7/watermarkedpdf"
        assert payload["preview_path"] == "documents/7/preview.pdf"

    @pytest.mark.asyncio
    async def test_url_has_no_duplicated_context_path(self):
        """回调 URL 不能出现 /api/v1/api/v1"""
        processor = DocumentProcessor()
        with patch("edam_worker.processors.document.settings.BACKEND_BASE_URL",
                   "http://edam-backend:8092/api/v1"):
            with patch("edam_worker.processors.document.requests.patch",
                       return_value=_Resp(200, "ok")) as m:
                await processor._notify_backend_status(7, 2, 2, None, None)
        assert m.call_args.args[0] == \
            "http://edam-backend:8092/api/v1/documents/7/status"

    @pytest.mark.asyncio
    async def test_sends_worker_token_header(self):
        """回调必须带 X-Worker-Token —— 后端是 fail-closed 的"""
        processor = DocumentProcessor()
        with patch("edam_worker.processors.document.settings.WORKER_TOKEN", "s3cr3t"):
            with patch("edam_worker.processors.document.requests.patch",
                       return_value=_Resp(200, "ok")) as m:
                await processor._notify_backend_status(7, 2, 2, None, None)
        assert m.call_args.kwargs["headers"].get("X-Worker-Token") == "s3cr3t"

    @pytest.mark.asyncio
    async def test_401_raises_permanent_error_without_retrying(self):
        """401 是配置问题 —— 立刻永久失败，不做无谓的重试"""
        processor = DocumentProcessor()
        with patch("edam_worker.processors.document.requests.patch",
                   return_value=_Resp(401, "unauthorized")) as m:
            with pytest.raises(PermanentWorkerError) as exc:
                await processor._notify_backend_status(7, 2, 2, None, None)
        assert "401" in str(exc.value)
        assert m.call_count == 1

    @pytest.mark.asyncio
    async def test_5xx_is_retried_then_raises(self):
        """5xx 是瞬时故障 —— 便宜地重试，重试用尽后冒泡"""
        processor = DocumentProcessor()
        with patch("edam_worker.processors.document.requests.patch",
                   return_value=_Resp(500, "boom")) as m:
            with pytest.raises(Exception) as exc:
                await processor._notify_backend_status(7, 2, 2, None, None)
        assert not isinstance(exc.value, PermanentWorkerError)
        assert m.call_count == 3

    @pytest.mark.asyncio
    async def test_transient_failure_then_success(self):
        processor = DocumentProcessor()
        with patch("edam_worker.processors.document.requests.patch",
                   side_effect=[requests.ConnectionError("refused"), _Resp(200, "ok")]) as m:
            await processor._notify_backend_status(7, 2, 2, None, None)
        assert m.call_count == 2


class TestDocumentProcessAlwaysReportsStatus:
    """process() 无论成败都必须回调 —— 否则状态永远停在 pending"""

    @pytest.fixture(autouse=True)
    def _fast_backoff(self, monkeypatch):
        monkeypatch.setattr(
            "edam_worker.processors.document.settings.WORKER_CALLBACK_RETRY_BACKOFF_SEC", 0.0
        )
        monkeypatch.setattr(
            "edam_worker.processors.document.settings.WORKER_CALLBACK_MAX_ATTEMPTS", 1
        )

    @pytest.mark.asyncio
    async def test_failure_still_reports_failed_status(self, monkeypatch):
        """下载失败 ⇒ 回调时两个状态都应是 failed(3)"""
        processor = DocumentProcessor()

        async def boom_download(*args, **kwargs):
            raise RuntimeError("minio_download_failed")

        monkeypatch.setattr(processor, "_download", boom_download)

        with patch("edam_worker.processors.document.requests.patch",
                   return_value=_Resp(200, "ok")) as m:
            with pytest.raises(RuntimeError):
                await processor.process(
                    {"doc_id": 7, "input_path": "uploads/7/a.pdf", "file_type": "pdf"}
                )

        payload = m.call_args.kwargs["json"]
        assert payload["watermark_status"] == 3, "失败必须报 failed(3)，不是 2"
        assert payload["preview_status"] == 3

    @pytest.mark.asyncio
    async def test_original_error_survives_callback_failure(self, monkeypatch):
        """下载失败 + 回调失败 ⇒ 抛出来的必须是**下载**那个错"""
        processor = DocumentProcessor()

        async def boom_download(*args, **kwargs):
            raise RuntimeError("minio_download_failed")

        monkeypatch.setattr(processor, "_download", boom_download)

        with patch("edam_worker.processors.document.requests.patch",
                   return_value=_Resp(401, "unauthorized")):
            with pytest.raises(RuntimeError) as exc:
                await processor.process(
                    {"doc_id": 7, "input_path": "uploads/7/a.pdf", "file_type": "pdf"}
                )
        assert "minio_download_failed" in str(exc.value), \
            "原始异常被回调异常顶替了 —— 排障会被带偏"

    @pytest.mark.asyncio
    async def test_watermark_disabled_reports_skipped(self, monkeypatch):
        """不启用水印 ⇒ watermark_status 报 skipped(4)，不是 ready"""
        processor = DocumentProcessor()

        async def fake_download(*args, **kwargs):
            return "/tmp/a.pdf"

        async def fake_convert(*args, **kwargs):
            return "/tmp/a.pdf"

        async def fake_upload(*args, **kwargs):
            return None

        monkeypatch.setattr(processor, "_download", fake_download)
        monkeypatch.setattr(processor, "_convert_to_preview", fake_convert)
        monkeypatch.setattr(processor, "_upload", fake_upload)

        with patch("edam_worker.processors.document.requests.patch",
                   return_value=_Resp(200, "ok")) as m:
            await processor.process(
                {"doc_id": 7, "input_path": "uploads/7/a.pdf",
                 "file_type": "pdf", "enable_watermark": False}
            )

        payload = m.call_args.kwargs["json"]
        assert payload["watermark_status"] == 4, "未启用水印应报 skipped(4)"
        assert payload["preview_status"] == 2
