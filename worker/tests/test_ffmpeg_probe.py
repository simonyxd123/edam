"""
FFmpeg 能力探测的测试

**为什么测这个**：2026-10-29 线上转码失败，抛出来的异常只有一句
"FFmpeg HLS 转码失败: 3" —— FFmpeg 自己的报错（缺编码器？缺滤镜？
输入文件坏了？）只在日志里，日志会被轮转、也会被 grep 漏掉。

所以 worker 启动时就要把环境能力查清楚，缺什么报什么。
这些用例锁住两件事：
1. 解析 FFmpeg 输出格式的能力（真实输出长什么样）；
2. 缺能力时**说清楚缺什么、怎么补**，而不是笼统地"探测失败"。
"""
from unittest.mock import patch

import pytest

from edam_worker.ffmpeg_probe import (
    _has_encoder,
    _has_filter,
    probe_ffmpeg,
    remediation,
)

# 真实的 ffmpeg -hide_banner -encoders 输出（截取）
ENCODERS_FULL = """Encoders:
 V..... = Video
 A..... = Audio
 S..... = Subtitle
 .F.... = Frame-level multithreading
 ..C... = Slice multithreading
 V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10
 V....D libx265              libx265 H.265 / HEVC
 V..... aac                  AAC (Advanced Audio Coding)
 A....D aac                  AAC (Advanced Audio Coding)
"""

# 精简版 ffmpeg（没有 libx264、没有 drawtext）—— 服务器上装错包时的典型输出
ENCODERS_MINIMAL = """Encoders:
 V..... = Video
 A..... = Audio
 V..... mpeg4                MPEG-4 part 2
"""

FILTERS_FULL = """Filters:
  T.. = Timeline support
 .F. = Frame filtering
 ... = Frame rate conversion
 ..C = Scaler
 ... abench            A->A       Benchmark part of a filtergraph.
 ... drawtext          V->V       Draw text on top of video frames.
 ... scale             V->V       Scale the input video size.
"""

FILTERS_MINIMAL = """Filters:
  T.. = Timeline support
 .F. = Frame filtering
 ... scale             V->V       Scale the input video size.
"""

VERSION_OUT = "ffmpeg version 5.1.6-0+deb12u1 Copyright (c) 2000-2023 the FFmpeg developers\n"


class _FakeProc:
    def __init__(self, stdout: str):
        self.stdout = stdout.encode("utf-8")
        self.returncode = 0


def _fake_run(mapping):
    """构造一个假的 subprocess.run：按命令行里的关键字返回对应输出。"""
    def _run(args, **kwargs):
        joined = " ".join(args)
        for key, out in mapping.items():
            if key in joined:
                return _FakeProc(out)
        return _FakeProc("")
    return _run


class TestParsing:
    def test_has_encoder(self):
        assert _has_encoder(ENCODERS_FULL, "libx264")
        assert _has_encoder(ENCODERS_FULL, "aac")
        assert not _has_encoder(ENCODERS_MINIMAL, "libx264")
        assert not _has_encoder(ENCODERS_MINIMAL, "aac")

    def test_has_filter(self):
        assert _has_filter(FILTERS_FULL, "drawtext")
        assert _has_filter(FILTERS_FULL, "scale")
        assert not _has_filter(FILTERS_MINIMAL, "drawtext")

    def test_header_lines_are_not_mistaken_for_names(self):
        """" V..... = Video" 这种表头不能被当成编码器名。"""
        assert not _has_encoder(ENCODERS_FULL, "Video")
        assert not _has_filter(FILTERS_FULL, "Timeline")


class TestProbe:
    def test_ok_when_everything_present(self):
        mapping = {
            "-version": VERSION_OUT,
            "-encoders": ENCODERS_FULL,
            "-filters": FILTERS_FULL,
        }
        with patch("subprocess.run", side_effect=_fake_run(mapping)):
            result = probe_ffmpeg("/usr/bin/ffmpeg", "/fonts/DejaVuSans-Bold.ttf")
        assert result["ok"] is True
        assert result["missing_encoders"] == []
        assert result["missing_filters"] == []
        assert "ffmpeg version" in result["version"]

    def test_reports_missing_encoder_and_filter(self):
        """精简版 ffmpeg ⇒ 说清楚缺 libx264 和 drawtext。"""
        mapping = {
            "-version": VERSION_OUT,
            "-encoders": ENCODERS_MINIMAL,
            "-filters": FILTERS_MINIMAL,
        }
        with patch("subprocess.run", side_effect=_fake_run(mapping)):
            result = probe_ffmpeg("/usr/bin/ffmpeg", "/fonts/DejaVuSans-Bold.ttf")
        assert result["ok"] is False
        assert result["missing_encoders"] == ["libx264", "aac"]
        assert result["missing_filters"] == ["drawtext"]

    def test_missing_font_is_reported(self):
        """没字体也是缺能力 —— drawtext 的 fontfile 找不到就是转码失败。"""
        mapping = {
            "-version": VERSION_OUT,
            "-encoders": ENCODERS_FULL,
            "-filters": FILTERS_FULL,
        }
        with patch("subprocess.run", side_effect=_fake_run(mapping)):
            result = probe_ffmpeg("/usr/bin/ffmpeg", None)
        assert result["ok"] is False
        assert result.get("font_missing") is True

    def test_missing_binary(self):
        """FFmpeg 路径不对 ⇒ 明确说"不可执行"，而不是笼统失败。"""
        with patch("subprocess.run", side_effect=FileNotFoundError("no ffmpeg")):
            result = probe_ffmpeg("/nope/ffmpeg", "/fonts/x.ttf")
        assert result["ok"] is False
        assert "不可执行" in result.get("error", "")

    def test_timeout_is_reported_not_crashed(self):
        with patch("subprocess.run", side_effect=TimeoutError("too slow")):
            result = probe_ffmpeg("/usr/bin/ffmpeg", "/fonts/x.ttf")
        assert result["ok"] is False
        assert "error" in result


class TestRemediation:
    def test_mentions_how_to_fix(self):
        result = {
            "ok": False,
            "missing_encoders": ["libx264"],
            "missing_filters": ["drawtext"],
            "font_missing": True,
        }
        tip = remediation(result)
        assert "libx264" in tip
        assert "drawtext" in tip
        assert "apt-get install" in tip

    def test_ok_message_when_nothing_missing(self):
        assert "能力齐全" == remediation({"ok": True})
