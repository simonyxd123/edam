"""
FFmpeg 能力探测

**为什么要有这个**：转码失败时，FFmpeg 自己会把原因写在 stderr 里，
但那行 stderr 只出现在**日志**中 —— 抛出去的异常只有一句
"FFmpeg HLS 转码失败: 3"，零诊断信息。2026-10-29 线上就是这样：
看到 journal 里只有一句 RuntimeError，根本不知道是编码器缺失、
滤镜缺失还是输入文件坏了。

这个模块把"环境能力"提前到 **worker 启动时**检查一遍：

    启动 → 探测 → 缺什么就报什么（含补救命令）
         → 第一个视频到来之前就知道能不能转

比"等第一个视频失败再猜"快一个数量级。
"""
import subprocess
from typing import Any, Dict, List, Optional

# HLS 流水线必需的编码器：视频 H.264 + 音频 AAC
REQUIRED_ENCODERS = ("libx264", "aac")

# 必需的滤镜：drawtext 是视频防泄密水印的核心
REQUIRED_FILTERS = ("drawtext",)


def _run(args: List[str], timeout: int = 20) -> Optional[str]:
    """跑一条 ffmpeg 命令，返回合并后的输出；跑不起来返回 None。"""
    try:
        proc = subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        return proc.stdout.decode("utf-8", errors="replace")
    except FileNotFoundError:
        return None
    except Exception as e:  # 超时、权限、崩了……都归到"跑不起来"
        return f"__ERROR__ {type(e).__name__}: {e}"


def _has_encoder(output: str, name: str) -> bool:
    """`-encoders` 的输出里每一行以标志位开头，编码器名是第 2 个字段。"""
    for line in output.splitlines():
        parts = line.split()
        # 形如 " V....D libx264   libx264 H.264 / AVC ..."
        if len(parts) >= 2 and parts[1] == name:
            return True
    return False


def _has_filter(output: str, name: str) -> bool:
    """`-filters` 的输出里每行形如 " ... drawtext   V->V  Draw text ..."。

    滤镜名总是**前两个** token 之一：第 1 个是标志位（"... " / "T.."），
    第 2 个才是名字。后面的 V->V 和描述都不可能是滤镜名。
    """
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2 and name in parts[:2]:
            return True
    return False


def probe_ffmpeg(ffmpeg_path: str, font_path: Optional[str] = None) -> Dict[str, Any]:
    """探测 FFmpeg 是否具备转码所需的能力。

    :param ffmpeg_path: FFmpeg 可执行文件路径
    :param font_path: drawtext 用的字体路径（None = 没找到字体）
    :return: 能力清单，``ok`` 为 True 表示可以正常转码
    """
    result: Dict[str, Any] = {
        "ok": False,
        "ffmpeg_path": ffmpeg_path,
        "missing_encoders": [],
        "missing_filters": [],
        "font_path": font_path,
    }

    version_out = _run([ffmpeg_path, "-hide_banner", "-version"])
    if version_out is None:
        result["error"] = (
            f"FFmpeg 不可执行：{ffmpeg_path}（路径不对？权限不足？）")
        return result
    if version_out.startswith("__ERROR__"):
        result["error"] = f"执行 FFmpeg 失败：{version_out}"
        return result

    first_line = version_out.splitlines()[0] if version_out.splitlines() else ""
    result["version"] = first_line.strip()

    encoders_out = _run([ffmpeg_path, "-hide_banner", "-encoders"]) or ""
    missing_encoders = [e for e in REQUIRED_ENCODERS if not _has_encoder(encoders_out, e)]
    result["missing_encoders"] = missing_encoders

    filters_out = _run([ffmpeg_path, "-hide_banner", "-filters"]) or ""
    missing_filters = [f for f in REQUIRED_FILTERS if not _has_filter(filters_out, f)]
    result["missing_filters"] = missing_filters

    # 字体没有也算缺失能力 —— drawtext 的 fontfile 找不到就是转码失败
    if not font_path:
        result["font_missing"] = True

    result["ok"] = not missing_encoders and not missing_filters and bool(font_path)
    return result


def remediation(result: Dict[str, Any]) -> str:
    """根据探测结果给出补救命令（Debian/Ubuntu 系）。"""
    tips: List[str] = []
    missing_encoders = result.get("missing_encoders") or []
    missing_filters = result.get("missing_filters") or []
    if missing_encoders:
        tips.append(
            "缺少编码器 " + ",".join(missing_encoders) +
            " ⇒ 装完整的 ffmpeg：apt-get install -y ffmpeg"
            "（精简版 ffmpeg 不含 libx264）"
        )
    if missing_filters:
        tips.append(
            "缺少滤镜 " + ",".join(missing_filters) +
            " ⇒ 需要带 libfreetype 的 ffmpeg：apt-get install -y ffmpeg"
            " fonts-dejavu-core（drawtext 依赖 freetype）"
        )
    if result.get("font_missing"):
        tips.append(
            "没找到水印字体 ⇒ apt-get install -y fonts-dejavu-core"
            "（或 fonts-liberation），否则 drawtext 无法工作"
        )
    if result.get("error"):
        tips.append(
            f"FFmpeg 跑不起来：{result['error']} ⇒ 检查 FFMPEG_PATH 配置与文件权限"
        )
    return " | ".join(tips) if tips else "能力齐全"
