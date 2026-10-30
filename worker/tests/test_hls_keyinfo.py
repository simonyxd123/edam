"""
HLS 加密密钥相关的契约测试

**为什么这个测试很重要**：2026-10-29 线上**每一条视频都转码失败**，
根因是 key.info 的第二行写法不对 —— 我连错两次：

    第一次以为第二行是"key 的十六进制"（文档里那么写的）
    第二次以为是"行序问题"，于是试了两种行序

真相（2026-10-30 查 FFmpeg 邮件列表归档确认）是：

    第 1 行  key 的 URI（写进 m3u8 的 #EXT-X-KEY）
    第 2 行  **key 文件的路径** —— FFmpeg 打开它，读前 16 字节
    第 3 行  可选 IV

把十六进制写在第二行 ⇒ FFmpeg 拿它当**文件名**去打开：

    [hls] Opening '42ccf808b90e...' for reading
    [hls muxer] error opening key file 42ccf808b90e...

而这个错误**一次都没被测出来** —— 没有测试碰过 key.info 的内容。
下面这几组用例把它锁死。
"""
import asyncio
import os

import pytest

from edam_worker.processors.video import VideoProcessor

KEY_HEX = "42ccf808b90e06e674095a71f25f1106"


def _key_info_lines(path) -> list:
    return path.read_text(encoding="utf-8").splitlines()


def _is_bare_hex(text: str) -> bool:
    """这一行是不是 32 位十六进制（key 的十六进制形式）。

    转码时 key 是随机生成的，所以只能判断"形状"，不能比对固定值。
    """
    return len(text) == 32 and all(c in "0123456789abcdef" for c in text)


class TestKeyFiles:
    """key.bin / key.info 的内容契约"""

    def test_key_bin_exists_and_holds_raw_key(self, tmp_path):
        """key.bin 必须**提前**写好，且是 16 字节原始 key。

        FFmpeg 会真的去打开 key.info 第二行指的那个 key 文件；
        而后端下发给播放器的是同一份字节 —— 两边必须完全一致，
        否则播放器解不开。
        """
        processor = VideoProcessor()
        key = os.urandom(16)

        processor._write_key_files(str(tmp_path), key)

        assert (tmp_path / "key.bin").exists(), "key.bin 必须在 FFmpeg 启动前写好"
        assert (tmp_path / "key.bin").read_bytes() == key

    def test_uri_keyfile_layout(self, tmp_path):
        """服务器上 FFmpeg 4.3.9 认的布局。

        第一行 = key 的 URI（写进 m3u8），第二行 = **key 文件的路径**。
        """
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)
        processor._write_key_files(str(tmp_path), key)

        processor._write_key_info(str(tmp_path), key, "uri_keyfile")

        lines = _key_info_lines(tmp_path / "key.info")
        assert lines[0] == "key.bin", "第一行必须是 key 的 URI"
        assert lines[1] == str(tmp_path / "key.bin"), \
            "第二行必须是 key 文件的路径（绝对路径），不是 key 的十六进制"

    def test_uri_hex_layout(self, tmp_path):
        """部分版本文档所述的布局：第二行写 key 的十六进制。"""
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)

        processor._write_key_info(str(tmp_path), key, "uri_hex")

        lines = _key_info_lines(tmp_path / "key.info")
        assert lines == ["key.bin", KEY_HEX]

    def test_no_uri_label_prefix(self, tmp_path):
        """第一行**不能**带 "URI " 前缀。

        那是把文档里 "first line is a URI to the key file" 的说明文字
        当成了文件内容 —— 曾经这么写，导致 FFmpeg 把 key 的十六进制
        当文件名去打开。
        """
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)

        processor._write_key_info(str(tmp_path), key, "uri_keyfile")

        for line in _key_info_lines(tmp_path / "key.info"):
            assert not line.startswith("URI "), "第一行不能带 'URI ' 前缀"

    def test_second_line_is_never_the_bare_hex(self, tmp_path):
        """第二行**绝不能**是裸的 32 位十六进制（除非明确选了 uri_hex）。

        这是 2026-10-29 全军覆没的直接原因 —— FFmpeg 把它当文件名去打开。
        """
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)
        processor._write_key_files(str(tmp_path), key)

        processor._write_key_info(str(tmp_path), key, "uri_keyfile")

        second = _key_info_lines(tmp_path / "key.info")[1]
        assert second != KEY_HEX, \
            "第二行写成裸十六进制 ⇒ FFmpeg 会把它当文件名去打开"
        assert os.path.exists(second), "第二行指向的文件必须真实存在"


class _FakeFfmpeg:
    """假的 FFmpeg：按调用次序返回预设结果，并记录每次的 key.info 内容。"""

    def __init__(self, results):
        # results: [{"returncode": int, "stderr": str, "playlist": str|None}, ...]
        self.results = results
        self.calls = []
        self.key_info_contents = []

    async def __call__(self, *cmd, **kwargs):
        self.calls.append({"cmd": cmd, "kwargs": kwargs})
        key_info = cmd[cmd.index("-hls_key_info_file") + 1]
        self.key_info_contents.append(open(key_info, encoding="utf-8").read())
        result = self.results[min(len(self.calls) - 1, len(self.results) - 1)]

        m3u8_path = cmd[-1]
        if result.get("playlist") is not None:
            with open(m3u8_path, "w", encoding="utf-8") as f:
                f.write(result["playlist"])

        class _Proc:
            returncode = result["returncode"]

            async def communicate(self):
                return b"", result.get("stderr", "").encode("utf-8")

        return _Proc()


_GOOD_PLAYLIST = (
    '#EXTM3U\n'
    '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
    '#EXTINF:10.0,\nsegment_000.ts\n'
)
_BAD_PLAYLIST = (
    '#EXTM3U\n'
    '#EXT-X-KEY:METHOD=AES-128,URI="42ccf808b90e06e674095a71f25f1106"\n'
    '#EXTINF:10.0,\nsegment_000.ts\n'
)


class TestLayoutFallback:
    """写法不对时的自适配"""

    @pytest.mark.asyncio
    async def test_retries_with_other_layout_on_key_file_error(self, tmp_path, monkeypatch):
        """第一种写法报"打不开 key 文件" ⇒ 换第二种，成功就用它。"""
        processor = VideoProcessor()
        fake = _FakeFfmpeg([
            {"returncode": 1, "stderr": "[hls muxer] error opening key file "
                                         "42ccf808b90e06e674095a71f25f1106"},
            {"returncode": 0, "stderr": "", "playlist": _GOOD_PLAYLIST},
        ])
        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        out_dir, _key = await processor._hls_transcode(
            str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
        )

        assert len(fake.calls) == 2, "第一种写法失败后应该换一种再试"
        # 第一种：uri_keyfile（第二行是路径）⇒ 第二行不是裸十六进制
        first_second_line = fake.key_info_contents[0].splitlines()[1]
        assert not _is_bare_hex(first_second_line), \
            "第一种写法（uri_keyfile）的第二行应该是路径而不是十六进制"
        # 第二种：uri_hex（第二行是十六进制）
        assert _is_bare_hex(fake.key_info_contents[1].splitlines()[1]), \
            "第二种写法（uri_hex）的第二行应该是 32 位十六进制"
        assert processor._key_info_layout == "uri_hex", "成功的写法要被记住"

    @pytest.mark.asyncio
    async def test_remembered_layout_is_tried_first(self, tmp_path, monkeypatch):
        """记住的写法排第一 —— 后续视频不用再白跑一次。"""
        processor = VideoProcessor()
        processor._key_info_layout = "uri_hex"
        fake = _FakeFfmpeg([
            {"returncode": 0, "stderr": "", "playlist": _GOOD_PLAYLIST},
        ])
        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        await processor._hls_transcode(
            str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
        )

        assert len(fake.calls) == 1, "记住的写法能用就不该重试"
        assert _is_bare_hex(fake.key_info_contents[0].splitlines()[1]), \
            "记住的是 uri_hex ⇒ 第二行应该是 32 位十六进制"

    @pytest.mark.asyncio
    async def test_wrong_key_uri_in_playlist_triggers_retry(self, tmp_path, monkeypatch):
        """转码"成功"但 m3u8 里的 key URI 不对 ⇒ 也要换写法重试。

        后端改写正则是 URI="key\\.bin"，URI 错了播放器就拿不到密钥 ——
        这种"假成功"必须被发现。
        """
        processor = VideoProcessor()
        fake = _FakeFfmpeg([
            {"returncode": 0, "stderr": "", "playlist": _BAD_PLAYLIST},
            {"returncode": 0, "stderr": "", "playlist": _GOOD_PLAYLIST},
        ])
        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        await processor._hls_transcode(
            str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
        )

        assert len(fake.calls) == 2, "key URI 不对必须重试，不能当成功"

    @pytest.mark.asyncio
    async def test_non_layout_error_is_not_retried(self, tmp_path, monkeypatch):
        """输入文件坏了这类错误**不能**被"换写法重试"掩盖 —— 立刻失败。"""
        processor = VideoProcessor()
        fake = _FakeFfmpeg([
            {"returncode": 1, "stderr": "Invalid data found when processing input"},
        ])
        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        with pytest.raises(RuntimeError) as exc:
            await processor._hls_transcode(
                str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
            )

        assert len(fake.calls) == 1, "与写法无关的错误不该重试"
        assert "Invalid data found" in str(exc.value), "原始报错必须带出来"

    @pytest.mark.asyncio
    async def test_ffmpeg_cwd_is_output_dir(self, tmp_path, monkeypatch):
        """FFmpeg 的 CWD 必须是 output_dir（相对路径的基准目录各版本不一致）。"""
        processor = VideoProcessor()
        fake = _FakeFfmpeg([
            {"returncode": 0, "stderr": "", "playlist": _GOOD_PLAYLIST},
        ])
        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        out_dir, _key = await processor._hls_transcode(
            str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
        )

        assert fake.calls[0]["kwargs"].get("cwd") == out_dir
