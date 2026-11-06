"""
内网盲水印提取端点的测试。

**为什么测这个**：此前 Java 后端的 `/watermarks/extract` 返回的是一个
**假的**"没查到泄露"——`confidence=0.0` + `matched_users=[]`，
而且什么都没扫过。那比不提供这个端点更危险：运维看到"没有匹配用户"
会得出"这个文件是干净的"这个**错误结论**。

所以这里盯的是三态必须分清：
  提取到 → extracted=true
  没水印 → extracted=false（**有效结论**）
  提取出错 → 报错，**绝不能**伪装成"没水印"
"""
import pytest
from fastapi.testclient import TestClient

from edam_worker.internal import router
from edam_worker.main import app


@pytest.fixture
def client():
    return TestClient(app)


def _post(client, file_type: str, content: bytes, token: str = "s3cr3t",
          extra_headers: dict | None = None):
    """带二进制 Content-Type 调提取端点。

    FastAPI 按**请求的** Content-Type 决定 body 解析方式 ——
    不带 application/octet-stream 会被当成 JSON 解析并报
    "JSON decode error"（后端客户端是带这个头的）。
    """
    headers = {
        "Content-Type": "application/octet-stream",
        "X-Worker-Token": token,
    }
    if extra_headers:
        headers.update(extra_headers)
    return client.post(
        f"/internal/watermark/extract?file_type={file_type}",
        content=content,
        headers=headers,
    )


class TestInternalAuth:
    """内网端点必须鉴权（fail-closed）"""

    def test_missing_token_rejected(self, client, monkeypatch):
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        resp = _post(client, "image", b"x", token="")
        assert resp.status_code == 403

    def test_wrong_token_rejected(self, client, monkeypatch):
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        resp = _post(client, "image", b"x", token="wrong")
        assert resp.status_code == 403

    def test_worker_token_not_configured_fails_closed(self, client, monkeypatch):
        """没配 WORKER_TOKEN → 一律 403（不是"放行"）"""
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "")
        resp = _post(client, "image", b"x", token="anything")
        assert resp.status_code == 403

    def test_oversized_body_rejected(self, client, monkeypatch):
        """超过提取上限 → 413"""
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        resp = _post(client, "image", b"x", extra_headers={"Content-Length": "999999999"})
        assert resp.status_code in (400, 413)

    def test_correct_token_passes_auth(self, client, monkeypatch):
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        # 文件类型不支持 → 400（说明鉴权已过）
        resp = _post(client, "docx", b"x")
        assert resp.status_code == 400


class TestExtractSemantics:
    def test_unsupported_type_is_400_not_empty_result(self, client, monkeypatch):
        """不支持的类型必须明确报错，不能返回"没提取到" """
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        resp = _post(client, "video", b"x")
        assert resp.status_code == 400

    def test_empty_body_is_400(self, client, monkeypatch):
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        resp = _post(client, "image", b"")
        assert resp.status_code == 400

    def test_no_watermark_returns_extracted_false(self, client, monkeypatch):
        """文件里没有水印 → extracted=false（有效结论），不是报错"""
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")
        # 随机字节当图片：提取不到水印，但也不该崩
        resp = _post(client, "image", b"\x00" * 512)
        assert resp.status_code == 200
        body = resp.json()
        assert body["extracted"] is False
        assert body["text"] == ""

    def test_extraction_error_is_not_reported_as_no_watermark(self, client, monkeypatch):
        """提取过程出错 → 必须报错，不能伪装成"没提取到"

        这是最关键的一条：把"系统故障"混进"有效结论"里，
        运维就会把一次失败的扫描当成"这个文件是干净的"。
        """
        monkeypatch.setattr("edam_worker.internal.settings.WORKER_TOKEN", "s3cr3t")

        class _Boom:
            def extract(self, path, file_type):
                raise RuntimeError("blind_watermark_broken")

        import edam_worker.processors.watermark as wm_mod
        monkeypatch.setattr(wm_mod, "WatermarkProcessor", _Boom)

        resp = _post(client, "image", b"\x00" * 512)
        assert resp.status_code == 422
        assert "blind_watermark_broken" in resp.json()["detail"]
