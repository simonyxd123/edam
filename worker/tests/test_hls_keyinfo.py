"""
HLS 加密密钥相关的契约测试

**为什么这个测试很重要**：2026-10-29 线上**每一条视频都转码失败**，
根因是 key.info 的格式写错了一行：

    URI key.bin            ← 错：把文档里的说明文字当成了文件内容
    42ccf808b90e...        ← FFmpeg 拿这行 hex 当成文件名去打开 ⇒ 失败

正确格式（FFmpeg 文档 ``hls_key_info_file``）是：

    key.bin                 ← 第一行就是 URI 本身
    42ccf808b90e...        ← 第二行是 key（32 位十六进制）

而这个错误**一次都没被测出来** —— 因为没有测试碰过 key.info 的内容。
下面这三个用例把它锁死。
"""
import asyncio
import os

import pytest

from edam_worker.processors.video import VideoProcessor


class TestKeyInfoFormat:
    """key.info / key.bin 的格式契约"""

    def test_key_info_has_uri_then_key_and_nothing_else(self, tmp_path):
        processor = VideoProcessor()
        key = os.urandom(16)

        processor._write_key_files(str(tmp_path), key)

        lines = (tmp_path / "key.info").read_text(encoding="utf-8").splitlines()
        assert len(lines) == 2, f"key.info 应该正好两行，实际 {lines!r}"
        assert lines[0] == "key.bin", (
            "第一行必须是 URI 本身，**不能带 'URI ' 前缀** —— "
            "FFmpeg 会把多出来的 token 当成别的东西，"
            "结果是拿第二行的 key 当文件名去打开，转码必然失败")
        assert lines[1] == key.hex(), "第二行必须是 key 的 32 位十六进制"

    def test_key_file_exists_before_ffmpeg_runs(self, tmp_path):
        """key.bin 必须在 FFmpeg 启动**之前**就存在。

        文档说第一行是 "URI to the **key file**" —— FFmpeg 可能真的去打开它。
        原实现是转码成功之后才写 key.bin，FFmpeg 运行时那个文件压根不存在。
        """
        processor = VideoProcessor()
        key = os.urandom(16)

        processor._write_key_files(str(tmp_path), key)

        assert (tmp_path / "key.bin").exists(), "key.bin 必须提前写好"
        assert (tmp_path / "key.bin").read_bytes() == key

    def test_m3u8_key_uri_matches_what_backend_rewrites(self, tmp_path):
        """m3u8 里的 URI 必须是 "key.bin"（相对路径）。

        后端 PlaybackController.rewriteM3u8 用正则 ``URI="key\\.bin"``
        把它改写成带 token 的绝对地址 —— 写成别的形式，后端就改写不了，
        播放器拿不到密钥 ⇒ 视频解不出来。
        """
        processor = VideoProcessor()
        processor._write_key_files(str(tmp_path), os.urandom(16))

        uri = (tmp_path / "key.info").read_text(encoding="utf-8").splitlines()[0]
        assert uri == "key.bin"


class TestFfmpegWorkingDirectory:
    """FFmpeg 的 CWD 必须是 output_dir"""

    @pytest.mark.asyncio
    async def test_ffmpeg_runs_with_output_dir_as_cwd(self, tmp_path, monkeypatch):
        """key.info 里的 URI 是相对路径，而 FFmpeg 解析相对 URI 的基准目录
        各版本不一致（有的是 key.info 所在目录，有的是进程 CWD）。

        把 CWD 设成 output_dir，两种解释都指向同一个文件。
        """
        processor = VideoProcessor()
        captured = {}

        async def fake_exec(*cmd, **kwargs):
            captured["cmd"] = cmd
            captured["cwd"] = kwargs.get("cwd")
            # 造一个"成功"的产物：m3u8 里必须有 EXT-X-KEY（_assert_encrypted 会查）
            m3u8_path = cmd[-1]
            with open(m3u8_path, "w", encoding="utf-8") as f:
                f.write('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n')
                f.write("#EXTINF:10.0,\nsegment_000.ts\n")

            class _Proc:
                returncode = 0

                async def communicate(self):
                    return b"", b""

            return _Proc()

        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        out_dir, _key = await processor._hls_transcode(
            str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
        )

        assert captured.get("cwd") == out_dir, (
            "FFmpeg 的 CWD 必须是 output_dir —— 否则相对 URI 'key.bin' "
            "解析到别的地方，FFmpeg 找不到 key 文件")
        # key.info 必须是绝对路径传给 FFmpeg（不能依赖 CWD）
        key_info_arg = captured["cmd"][captured["cmd"].index("-hls_key_info_file") + 1]
        assert os.path.isabs(key_info_arg)
