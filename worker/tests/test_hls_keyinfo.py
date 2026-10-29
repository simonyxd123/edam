"""
HLS 加密密钥相关的契约测试

**为什么这个测试很重要**：2026-10-29 线上**每一条视频都转码失败**，
根因是 key.info 的行序和本机 FFmpeg 认的不一致：

    文档说：第一行 URI、第二行 key
    FFmpeg 4.3.9 实测：它把**第二行**当文件名去打开
        [hls] Opening '42ccf808b90e...'(key 的十六进制) for reading
        [hls muxer] error opening key file 42ccf808b90e...

而这个错误**一次都没被测出来** —— 因为没有测试碰过 key.info 的内容，
也没有测试覆盖"行序不对时该怎么办"。

下面这几组用例把它锁死。
"""
import asyncio
import os

import pytest

from edam_worker.processors.video import VideoProcessor

KEY_HEX = "42ccf808b90e06e674095a71f25f1106"


def _key_info_lines(path) -> list:
    return path.read_text(encoding="utf-8").splitlines()


class TestKeyFiles:
    """key.bin / key.info 的内容契约"""

    def test_key_bin_exists_and_holds_raw_key(self, tmp_path):
        """key.bin 必须**提前**写好，且是 16 字节原始 key。

        FFmpeg 会真的去打开 key.info 里指的那个 key 文件；
        而后端下发给播放器的是同一份字节 —— 两边必须完全一致，
        否则播放器解不开。
        """
        processor = VideoProcessor()
        key = os.urandom(16)

        processor._write_key_files(str(tmp_path), key)

        assert (tmp_path / "key.bin").exists(), "key.bin 必须在 FFmpeg 启动前写好"
        assert (tmp_path / "key.bin").read_bytes() == key

    def test_uri_first_layout(self, tmp_path):
        """文档规定的布局：第一行 URI，第二行 key。"""
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)

        processor._write_key_info(str(tmp_path), key, "uri_first")

        lines = _key_info_lines(tmp_path / "key.info")
        assert lines == ["key.bin", KEY_HEX]

    def test_key_first_layout(self, tmp_path):
        """FFmpeg 4.3.9 实测认的布局：第一行 key，第二行 URI。"""
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)

        processor._write_key_info(str(tmp_path), key, "key_first")

        lines = _key_info_lines(tmp_path / "key.info")
        assert lines == [KEY_HEX, "key.bin"]

    def test_no_uri_label_prefix(self, tmp_path):
        """第一行**不能**带 "URI " 前缀。

        那是把文档里 "first line is a URI to the key file" 的说明文字
        当成了文件内容 —— 曾经这么写，导致 FFmpeg 把 key 的十六进制
        当文件名去打开。
        """
        processor = VideoProcessor()
        key = bytes.fromhex(KEY_HEX)

        processor._write_key_info(str(tmp_path), key, "uri_first")

        for line in _key_info_lines(tmp_path / "key.info"):
            assert not line.startswith("URI "), "第一行不能带 'URI ' 前缀"


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
    """行序不对时的自适配"""

    @pytest.mark.asyncio
    async def test_retries_with_other_layout_on_key_file_error(self, tmp_path, monkeypatch):
        """第一种布局报"打不开 key 文件" ⇒ 换第二种，成功就用它。"""
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

        assert len(fake.calls) == 2, "第一种布局失败后应该换一种再试"
        assert fake.key_info_contents[0].splitlines()[0] == "key.bin"
        assert fake.key_info_contents[1].splitlines()[0] != "key.bin"
        assert processor._key_info_layout == "key_first", "成功的布局要被记住"

    @pytest.mark.asyncio
    async def test_remembered_layout_is_tried_first(self, tmp_path, monkeypatch):
        """记住的布局排第一 —— 后续视频不用再白跑一次。"""
        processor = VideoProcessor()
        processor._key_info_layout = "key_first"
        fake = _FakeFfmpeg([
            {"returncode": 0, "stderr": "", "playlist": _GOOD_PLAYLIST},
        ])
        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake)

        work_dir = tmp_path / "work"
        work_dir.mkdir()
        await processor._hls_transcode(
            str(tmp_path / "in.mp4"), 1, "EDAM E000001", str(work_dir)
        )

        assert len(fake.calls) == 1, "记住的布局能用就不该重试"
        assert fake.key_info_contents[0].splitlines()[0] != "key.bin"

    @pytest.mark.asyncio
    async def test_wrong_key_uri_in_playlist_triggers_retry(self, tmp_path, monkeypatch):
        """转码"成功"但 m3u8 里的 key URI 不对 ⇒ 也要换布局重试。

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
        """输入文件坏了这类错误**不能**被"换布局重试"掩盖 —— 立刻失败。"""
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

        assert len(fake.calls) == 1, "与行序无关的错误不该重试"
        assert "Invalid data found" in str(exc.value), "原始报错必须带出来"

    @pytest.mark.asyncio
    async def test_ffmpeg_cwd_is_output_dir(self, tmp_path, monkeypatch):
        """key.info 里是相对路径 URI ⇒ FFmpeg 的 CWD 必须是 output_dir。"""
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
