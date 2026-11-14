"""
水印完整性测试（2026-11-14 代码审计第六轮）。

盯三件事：

1. **嵌入失败不许静默产出"无指纹原件"**
   —— 此前 PDF / Word / Excel 三条路径都是
   ``except Exception: shutil.copy(原文件)``：
   嵌入失败时原文照常流转、**完全没有指纹**，
   而流水线仍把 ``watermark_status`` 报成 ``2 (ready)``
   ⇒ 系统认定"已加水印"，溯源链**静默断裂**，监控上还什么都看不到。
   现在失败必须抛 ``PermanentWorkerError``。

2. **盲水印密码不许硬编码**
   —— 此前 embed / extract 三处全是 ``password_wm=1``，
   等于把"提取 / 伪造 / 抹除"的门槛设成零。

3. **实际嵌入的指纹要回写给后端**
   —— 没有这份登记，指纹就是可伪造的（第五轮 §3.3 的残留缺口）。
"""
import asyncio
from unittest.mock import MagicMock, patch

import pytest

from edam_worker.errors import PermanentWorkerError
from edam_worker.processors.watermark import WatermarkProcessor


class TestEmbedFailureIsNotSilent:
    """嵌入失败 ⇒ 抛错，不产出无指纹原件"""

    def test_pdf_failure_raises_instead_of_copying(self, tmp_path, monkeypatch):
        src = tmp_path / "in.pdf"
        src.write_bytes(b"%PDF-1.4 fake")
        out = tmp_path / "out.pdf"

        # 让 PyPDF2 的 import 或读取失败
        import builtins
        real_import = builtins.__import__

        def fake_import(name, *args, **kwargs):
            if name == "PyPDF2":
                raise ImportError("boom")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", fake_import)

        with pytest.raises(PermanentWorkerError):
            WatermarkProcessor().embed_pdf(str(src), str(out), "10|7|2026-11-14T10:00:00Z")

        # 关键：**不能**产出一个"看起来处理好了"的原件
        assert not out.exists(), "失败时不应写出任何文件（更不能是原文件的拷贝）"

    def test_docx_failure_raises_instead_of_copying(self, tmp_path, monkeypatch):
        src = tmp_path / "in.docx"
        src.write_bytes(b"not a real docx")
        out = tmp_path / "out.docx"

        import builtins
        real_import = builtins.__import__

        def fake_import(name, *args, **kwargs):
            if name == "docx":
                raise ImportError("boom")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", fake_import)

        with pytest.raises(PermanentWorkerError):
            WatermarkProcessor()._docx_steganography(
                str(src), str(out), "10|7|2026-11-14T10:00:00Z")
        assert not out.exists()

    def test_xlsx_failure_raises_instead_of_copying(self, tmp_path, monkeypatch):
        src = tmp_path / "in.xlsx"
        src.write_bytes(b"not a real xlsx")
        out = tmp_path / "out.xlsx"

        import builtins
        real_import = builtins.__import__

        def fake_import(name, *args, **kwargs):
            if name == "openpyxl":
                raise ImportError("boom")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", fake_import)

        with pytest.raises(PermanentWorkerError):
            WatermarkProcessor()._xlsx_steganography(
                str(src), str(out), "10|7|2026-11-14T10:00:00Z")
        assert not out.exists()


class TestWatermarkPasswordIsConfigurable:
    """密码来自部署配置，不是硬编码"""

    def test_embed_uses_configured_password(self, tmp_path, monkeypatch):
        monkeypatch.setattr(
            "edam_worker.processors.watermark.settings.EDAM_WATERMARK_PASSWORD", 42
        )
        src = tmp_path / "in.png"
        out = tmp_path / "out.png"

        captured = {}

        class FakeWaterMark:
            def __init__(self, password_wm=1, password_img=1):
                captured["password_wm"] = password_wm
                captured["password_img"] = password_img

            def read_img(self, path):
                captured["img"] = path

            def read_wm(self, wm, mode="bytes"):
                captured["wm"] = wm

            def embed(self, path):
                captured["out"] = path

        import sys
        import types
        fake_module = types.ModuleType("blind_watermark")
        fake_module.WaterMark = FakeWaterMark
        monkeypatch.setitem(sys.modules, "blind_watermark", fake_module)

        # 造一个能被 PIL 打开的最小 PNG
        from PIL import Image
        Image.new("RGB", (8, 8), "white").save(src)

        WatermarkProcessor().embed_image(str(src), str(out), "10|7|t")
        assert captured["password_wm"] == 42, "必须用配置里的密码，不能写死 1"
        assert captured["password_img"] == 42

    def test_extract_falls_back_to_legacy_password_one(self, tmp_path, monkeypatch):
        """配置成新密码时，提取仍要能解开本功能上线前用密码 1 嵌入的水印"""
        monkeypatch.setattr(
            "edam_worker.processors.watermark.settings.EDAM_WATERMARK_PASSWORD", 42
        )
        tried = []

        class FakeWaterMark:
            def __init__(self, password_wm=1, password_img=1):
                self.password_wm = password_wm
                tried.append(password_wm)

            def extract(self, path, mode="bytes"):
                if self.password_wm == 1:
                    return b"10|7|legacy"
                raise ValueError("wrong password")

        import sys
        import types
        fake_module = types.ModuleType("blind_watermark")
        fake_module.WaterMark = FakeWaterMark
        monkeypatch.setitem(sys.modules, "blind_watermark", fake_module)

        path = tmp_path / "x.png"
        path.write_bytes(b"whatever")
        text = WatermarkProcessor()._extract_image(str(path))
        assert text == "10|7|legacy"
        assert tried == [42, 1], "应先试配置的密码，再退回历史硬编码的 1"


class TestFingerprintIsReportedToBackend:
    """实际嵌入的指纹必须回写给后端做登记"""

    def test_status_callback_carries_fingerprint(self):
        from edam_worker.processors.document import DocumentProcessor

        proc = DocumentProcessor.__new__(DocumentProcessor)  # 不跑 __init__（它要连 MinIO）
        captured = {}

        def fake_patch(url, json=None, headers=None, timeout=None):
            # requests.patch 是同步的（被 asyncio.to_thread 包了一层）
            captured["url"] = url
            captured["payload"] = json
            return MagicMock(status_code=200, text="ok")

        with patch("edam_worker.processors.document.requests.patch", fake_patch):
            asyncio.run(proc._notify_backend_status(
                7, 2, 2,
                "documents/7/watermarked.pdf", "documents/7/preview.pdf",
                "10|7|2026-11-14T10:00:00Z",
            ))

        assert captured["payload"]["watermark_fingerprint"] == "10|7|2026-11-14T10:00:00Z"
