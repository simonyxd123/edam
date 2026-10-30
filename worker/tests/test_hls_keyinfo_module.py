"""
key.info 格式模块的契约测试

**这个模块存在的理由**：2026-10-29 线上每一条视频都转码失败，
根因是 key.info 第二行写成了 key 的十六进制，而 FFmpeg 把第二行
当**文件名**去打开：

    [hls] Opening '42ccf808b90e...' for reading
    [hls muxer] error opening key file 42ccf808b90e...

我连错两次（第一次以为是格式、第二次以为是行序），
所以这里的用例要把**格式本身**钉死。
"""
import os

import pytest

from edam_worker.hls_keyinfo import (
    KEY_INFO_LAYOUTS,
    KEY_URI,
    layout_order,
    looks_like_key_info_layout_error,
    playlist_key_uri_is_correct,
    probe_key_info_layout,
    write_key_bin,
    write_key_info,
)


class TestWriteKeyInfo:
    def test_uri_keyfile_second_line_is_the_key_file_path(self, tmp_path):
        """uri_keyfile：第二行是 **key 文件的路径**，不是 key 的十六进制。

        这是服务器上 FFmpeg 4.3.9 认的写法。
        """
        key = bytes.fromhex("42ccf808b90e06e674095a71f25f1106")
        write_key_bin(str(tmp_path), key)

        write_key_info(str(tmp_path), key, "uri_keyfile")

        lines = (tmp_path / "key.info").read_text(encoding="utf-8").splitlines()
        assert lines[0] == KEY_URI
        assert lines[1] == str(tmp_path / KEY_URI), \
            "第二行必须是 key 文件的路径"
        assert os.path.exists(lines[1]), "第二行指向的文件必须存在"

    def test_uri_hex_second_line_is_the_hex_key(self, tmp_path):
        """uri_hex：第二行是 key 的十六进制（部分版本文档所述）。"""
        key = bytes.fromhex("42ccf808b90e06e674095a71f25f1106")

        write_key_info(str(tmp_path), key, "uri_hex")

        lines = (tmp_path / "key.info").read_text(encoding="utf-8").splitlines()
        assert lines == [KEY_URI, key.hex()]

    def test_first_line_never_has_uri_prefix(self, tmp_path):
        """第一行不能带 "URI " 前缀 —— 那是文档里的说明文字，不是内容。"""
        key = os.urandom(16)
        write_key_bin(str(tmp_path), key)

        for layout in KEY_INFO_LAYOUTS:
            write_key_info(str(tmp_path), key, layout)
            for line in (tmp_path / "key.info").read_text(
                    encoding="utf-8").splitlines():
                assert not line.startswith("URI ")

    def test_key_bin_holds_raw_16_bytes(self, tmp_path):
        """key.bin 是 16 字节**原始** key（不是十六进制文本）。

        后端下发给播放器的是同一份字节 —— 两边必须完全一致，
        否则播放器解不开。
        """
        key = os.urandom(16)

        write_key_bin(str(tmp_path), key)

        assert (tmp_path / KEY_URI).read_bytes() == key
        assert len((tmp_path / KEY_URI).read_bytes()) == 16


class TestPlaylistValidation:
    def test_accepts_correct_uri(self, tmp_path):
        m3u8 = tmp_path / "p.m3u8"
        m3u8.write_text(
            '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n', encoding="utf-8")

        assert playlist_key_uri_is_correct(str(m3u8))

    def test_rejects_hex_uri(self, tmp_path):
        """假成功：FFmpeg 成功退出但把十六进制当 URI 写进了 m3u8。

        后端改写正则是 URI="key\\.bin"，URI 错了播放器就拿不到密钥。
        """
        m3u8 = tmp_path / "p.m3u8"
        m3u8.write_text(
            '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="42ccf808b90e"\n',
            encoding="utf-8")

        assert not playlist_key_uri_is_correct(str(m3u8))

    def test_missing_file_is_not_correct(self, tmp_path):
        assert not playlist_key_uri_is_correct(str(tmp_path / "nope.m3u8"))


class TestErrorClassification:
    @pytest.mark.parametrize("stderr", [
        "[hls muxer] error opening key file 42ccf808b90e",
        "[hls] Unable to open key file foo.key",
        "error reading key file ./foo.key",
        "Invalid key size: 4",
        "Invalid hex string",
    ])
    def test_layout_errors_are_detected(self, stderr):
        assert looks_like_key_info_layout_error(stderr)

    @pytest.mark.parametrize("stderr", [
        "Invalid data found when processing input",
        "Unknown encoder 'libx264'",
        "No such file or directory",
        "Error initializing output stream",
    ])
    def test_real_errors_are_not_misclassified(self, stderr):
        """输入文件坏了这类错误**不能**被"换写法重试"掩盖。"""
        assert not looks_like_key_info_layout_error(stderr)


class TestLayoutOrder:
    def test_detected_layout_comes_first(self):
        assert layout_order("uri_hex")[0] == "uri_hex"
        assert layout_order("uri_hex") == ["uri_hex", "uri_keyfile"]

    def test_unknown_detection_falls_back_to_all(self):
        assert layout_order(None) == list(KEY_INFO_LAYOUTS)
        assert layout_order("garbage") == list(KEY_INFO_LAYOUTS)


class TestProbe:
    def test_probe_reports_failure_when_ffmpeg_missing(self):
        """FFmpeg 不可执行时要明确报错，而不是抛异常。"""
        result = probe_key_info_layout("/nonexistent/ffmpeg")

        assert result["layout"] is None
        assert result["error"] is not None

    def test_probe_records_every_attempt(self, monkeypatch, tmp_path):
        """探测失败时，每种写法的尝试结果都要留痕 —— 否则日志里是黑洞。"""
        class _Proc:
            returncode = 1

            def __init__(self, out):
                self.stdout = out

        calls = []

        def _fake_run(cmd, **kwargs):
            calls.append(cmd)
            return _Proc(b"error opening key file 42ccf808b90e")

        monkeypatch.setattr("edam_worker.hls_keyinfo.subprocess.run", _fake_run)

        result = probe_key_info_layout("/usr/bin/ffmpeg")

        assert result["layout"] is None
        assert len(calls) == len(KEY_INFO_LAYOUTS)
        assert len(result["tried"]) == len(KEY_INFO_LAYOUTS)
        assert all(not t["ok"] for t in result["tried"])

    def test_probe_accepts_first_working_layout(self, monkeypatch):
        """第一种写法成功就返回它 —— 探测不能白跑第二种。"""
        class _Proc:
            returncode = 0

            def __init__(self, out):
                self.stdout = out

        def _fake_run(cmd, **kwargs):
            # 模拟 FFmpeg 成功并写出了正确的 m3u8
            m3u8 = cmd[-1]
            with open(m3u8, "w", encoding="utf-8") as f:
                f.write('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n')
            return _Proc(b"")

        monkeypatch.setattr("edam_worker.hls_keyinfo.subprocess.run", _fake_run)

        result = probe_key_info_layout("/usr/bin/ffmpeg")

        assert result["layout"] == KEY_INFO_LAYOUTS[0]
        assert len(result["tried"]) == 1
