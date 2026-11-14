"""
Worker 启动守卫：仓库里公开的占位密钥 ⇒ 非开发环境拒绝启动。

**为什么要守这条**（2026-11-14 代码审计第七轮）：
``WORKER_TOKEN`` 是 Worker → Backend 的共享密钥。持有它就能调用
``PATCH /documents/{id}/status`` ——

- 把任意文档的 ``watermark_status`` 改成 2(ready)
  ⇒ 一份**根本没有水印**的文档被标记成"已加水印"放行，溯源链断裂，
  而系统自己还认为它是完好的；
- 往水印指纹登记表里写入伪造指纹
  ⇒ 事后比对时"对不上" ⇒ 真的泄密被判定为"伪造"而洗白；
  或者反过来，栽赃给一个无辜员工。

而这个值在仓库里就是公开的：helm base 是 ``change-me-in-production``，
prod overlay 是 ``REPLACE_FROM_VAULT``，staging overlay 连这一行都没写
⇒ 直接继承 base 的公开值。

External Secrets 那条链路当时是**断的**（从未被 ArgoCD 下发），
于是"Worker 与 Backend 两边读到同一个公开字符串"这个约束
反而把公开值**坐实**成了生产实际使用的值 —— 校验自然通过。
"""

import pytest

from edam_worker.config import (
    KNOWN_SECRET_PLACEHOLDERS,
    known_placeholder,
)


class TestKnownPlaceholder:
    def test_matches_repo_published_values(self):
        # 这三个就是仓库里真实存在的公开值
        assert known_placeholder("change-me-in-production") == "change-me-in-production"
        assert known_placeholder("REPLACE_FROM_VAULT") == "replace_from_vault"
        assert known_placeholder("dev-jwt-secret-not-for-production") == ""

    def test_is_case_insensitive(self):
        assert known_placeholder("Change-Me-In-Production") == "change-me-in-production"

    def test_strips_whitespace(self):
        assert known_placeholder("  change-me-in-production  ") == "change-me-in-production"

    def test_real_secret_is_not_matched(self):
        real = "b7f3c1a95d2e8046a1f3c9e7d5b2048ac1e7d3b95f204a6c8e1d7b3f5a9c2e604d"
        assert known_placeholder(real) == ""

    def test_empty_is_not_matched(self):
        assert known_placeholder("") == ""
        assert known_placeholder(None) == ""

    def test_containment_is_not_enough(self):
        # 只比对完全相等 —— 包含匹配会把真实密钥里含 "secret" 的误判成占位符
        assert known_placeholder("my-secret-value-1234") == ""

    def test_placeholder_list_is_not_empty(self):
        assert "change-me-in-production" in KNOWN_SECRET_PLACEHOLDERS
        assert "replace_from_vault" in KNOWN_SECRET_PLACEHOLDERS


class TestLaxEnvironments:
    def test_only_dev_like_environments_are_lax(self):
        from edam_worker.config import LAX_ENVIRONMENTS

        # staging 不是开发环境 —— 它连着真实依赖
        assert "staging" not in LAX_ENVIRONMENTS
        assert "production" not in LAX_ENVIRONMENTS
        assert "dev" in LAX_ENVIRONMENTS


class TestWorkerStartupGuard:
    """Worker 的 lifespan 启动路径：占位符 ⇒ 拒绝启动。"""

    @pytest.mark.asyncio
    async def test_placeholder_token_blocks_startup_outside_dev(self, monkeypatch):
        from edam_worker import main as worker_main

        monkeypatch.setattr(worker_main.settings, "WORKER_TOKEN", "REPLACE_FROM_VAULT")
        monkeypatch.setattr(worker_main.settings, "EDAM_ENV", "production")

        with pytest.raises(RuntimeError) as exc:
            await worker_main.WorkerApp().start()

        assert "占位符" in str(exc.value)

    @pytest.mark.asyncio
    async def test_placeholder_token_blocks_startup_in_staging(self, monkeypatch):
        from edam_worker import main as worker_main

        monkeypatch.setattr(worker_main.settings, "WORKER_TOKEN", "change-me-in-production")
        monkeypatch.setattr(worker_main.settings, "EDAM_ENV", "staging")

        with pytest.raises(RuntimeError):
            await worker_main.WorkerApp().start()

    @pytest.mark.asyncio
    async def test_dev_environment_tolerates_placeholder(self, monkeypatch):
        """开发环境放行 —— 本地开发用的就是这批值，拦了寸步难行。

        守卫之后 start() 还会做 ffmpeg / key.info 能力自检，
        那两步要真的跑 ffmpeg（测试环境没装）⇒ 这里把它们替换成空实现。
        """
        from edam_worker import main as worker_main

        monkeypatch.setattr(worker_main.settings, "WORKER_TOKEN", "change-me-in-production")
        monkeypatch.setattr(worker_main.settings, "EDAM_ENV", "dev")

        async def _noop(*args, **kwargs):
            return None

        worker = worker_main.WorkerApp()
        monkeypatch.setattr(worker, "_probe_ffmpeg", _noop)
        monkeypatch.setattr(worker, "_probe_key_info_layout", _noop)

        # 不抛异常 = 守卫放行了
        await worker.start()

    @pytest.mark.asyncio
    async def test_empty_token_does_not_block_startup(self, monkeypatch):
        """没配 token ≠ 用公开密钥 —— 只是回调会被一律拒绝（fail-closed）。"""
        from edam_worker import main as worker_main

        monkeypatch.setattr(worker_main.settings, "WORKER_TOKEN", "")
        monkeypatch.setattr(worker_main.settings, "EDAM_ENV", "production")

        async def _noop(*args, **kwargs):
            return None

        worker = worker_main.WorkerApp()
        monkeypatch.setattr(worker, "_probe_ffmpeg", _noop)
        monkeypatch.setattr(worker, "_probe_key_info_layout", _noop)

        await worker.start()
