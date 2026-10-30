"""
HLS 加密密钥文件（key.info）的格式契约

**这个模块存在的唯一理由**：2026-10-29 线上**每一条视频都转码失败**，
根因是 key.info 的第二行写法不对。而我连错两次：

    第一次：以为第二行是"key 的十六进制"（官方文档那么写的）
    第二次：以为是"行序问题"，于是试了两种行序

真相（2026-10-30 查 FFmpeg 邮件列表归档 + 用服务器日志反推确认）：

    ==========  ==========================================================
    行           含义
    ==========  ==========================================================
    第 1 行      key 的 **URI** —— 写进 m3u8 的 ``#EXT-X-KEY``。
                 必须写 ``key.bin``，后端改写正则 ``URI="key\\.bin"``
                 才认。
    第 2 行      **key 文件的路径** —— FFmpeg 会真的去打开这个文件，
                 读它的前 16 字节当 AES key。
                 （旧版文档说这里写 key 的十六进制，新版行为是路径。）
    第 3 行      可选：IV（32 位十六进制）。不写则每个分片用序号当 IV。
    ==========  ==========================================================

**第二行从来不是 key 本身** —— 把 32 位十六进制写在那里，
FFmpeg 会拿它当**文件名**去打开：

    [hls] Opening '42ccf808b90e06e674095a71f25f1106' for reading
    [hls muxer] error opening key file 42ccf808b90e06e674095a71f25f1106

⇒ 每一条视频都失败，且报错极具误导性（看着像"文件缺失"）。

**为什么还要两种布局都支持**：各版本对第二行的处理不一致
（服务器上的 4.3.9 要路径，新版文档说要是十六进制）。
与其赌哪一种（赌错 = 每条视频都失败），不如：

    1. 启动时用一段 1 秒的合成视频**探测**出本机认哪种（探测函数）
    2. 转码时以探测结果为准，失败再退回逐个试

探测省掉了"每条视频先失败一次"的代价，也让日志能直接说明
本机 FFmpeg 认哪种格式。
"""
import os
import shutil
import subprocess
import tempfile
from typing import Dict, List, Optional

# 两种第二行写法，按"服务器实测优先"排序
KEY_INFO_LAYOUTS = ("uri_keyfile", "uri_hex")

# 写进 m3u8 的 key URI —— 必须与后端改写正则一致
KEY_URI = "key.bin"


def write_key_info(output_dir: str, key: bytes, layout: str) -> str:
    """按指定布局写 key.info，返回文件路径。

    :param output_dir: HLS 输出目录（key.bin 也在这里）
    :param key: 16 字节原始 key
    :param layout: ``uri_keyfile``（第二行=key 文件路径）
                   或 ``uri_hex``（第二行=key 的十六进制）
    """
    key_info_path = os.path.join(output_dir, "key.info")
    uri_line = KEY_URI
    if layout == "uri_hex":
        lines = [uri_line, key.hex()]
    else:
        # **绝对路径** —— FFmpeg 解析第二行相对路径的基准目录各版本不一致
        # （有的是 key.info 所在目录，有的是进程 CWD），用绝对路径绕开差异
        lines = [uri_line, os.path.join(output_dir, KEY_URI)]
    with open(key_info_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(line + "\n" for line in lines))
    return key_info_path


def write_key_bin(output_dir: str, key: bytes) -> str:
    """写 key.bin —— **必须在 FFmpeg 启动之前**写好。

    FFmpeg 会真的去打开它（``[hls] Opening '<key 文件>' for reading``），
    而转码成功之后才写的话，FFmpeg 运行时那个文件压根不存在。

    内容是 **16 字节原始 key** —— 与后端下发给播放器的是同一份字节，
    两边必须完全一致，否则播放器解不开。
    """
    key_path = os.path.join(output_dir, KEY_URI)
    with open(key_path, "wb") as f:
        f.write(key)
    return key_path


def playlist_key_uri_is_correct(m3u8_path: str) -> bool:
    """m3u8 里的 EXT-X-KEY URI 必须是 ``key.bin``。

    **这不只是"有没有加密"的问题** —— 后端
    ``PlaybackController.rewriteM3u8`` 用正则 ``URI="key\\.bin"``
    把它改写成带 token 的绝对地址。URI 写错了（哪怕加密生效了），
    后端改写不了 ⇒ 播放器拿不到密钥 ⇒ 视频解不开。

    2026-10-30 实测：key.info 第一行写成 key 的十六进制时，FFmpeg
    会**成功退出**但把十六进制当 URI 写进 m3u8 ——
    这种"假成功"必须被发现，而不是"转码成功"就当成功。
    """
    try:
        with open(m3u8_path, "r", encoding="utf-8", newline="") as f:
            for line in f:
                if line.startswith("#EXT-X-KEY"):
                    return f'URI="{KEY_URI}"' in line
    except OSError:
        return False
    return False


def looks_like_key_info_layout_error(stderr_text: str) -> bool:
    """这段报错是不是"key.info 第二行写法不对"引起的。

    只认明确跟 key 文件 / key 解析有关的说法；
    其它报错（输入文件坏了、编码器缺失……）必须原样抛出去，
    不能被"换种写法再试"掩盖。
    """
    lowered = stderr_text.lower()
    return ("error opening key file" in lowered
            or "unable to open key file" in lowered
            or "error reading key file" in lowered
            or "invalid key size" in lowered
            or "invalid hex" in lowered
            or ("could not write header for output file" in lowered
                and "key" in lowered))


def probe_key_info_layout(ffmpeg_path: str, timeout: int = 30) -> Dict[str, object]:
    """探测本机 FFmpeg 认哪种 key.info 第二行写法。

    用一段 **1 秒的合成视频**（lavfi testsrc）做试验 ——
    不需要真实素材，也不需要 drawtext 字体，所以很快（~1 秒），
    而且不会污染任何业务数据。

    每种写法都试一遍，判据是**产物**：FFmpeg 退出码为 0
    **且** m3u8 里的 EXT-X-KEY URI 确实是 ``key.bin``
    （只看退出码会踩"假成功"：FFmpeg 成功退出但把十六进制
    当 URI 写进了 m3u8 ⇒ 后端改写不了 ⇒ 播放器拿不到密钥）。

    :return: ``{"layout": "uri_keyfile" | "uri_hex" | None,
               "tried": [...], "error": str | None}``
    """
    result: Dict[str, object] = {"layout": None, "tried": [], "error": None}
    work_dir = tempfile.mkdtemp(prefix="edam-keyinfo-probe-")
    try:
        key = os.urandom(16)
        write_key_bin(work_dir, key)
        key_info = os.path.join(work_dir, "key.info")
        m3u8_path = os.path.join(work_dir, "out.m3u8")
        segment_pattern = os.path.join(work_dir, "seg_%03d.ts")

        for layout in KEY_INFO_LAYOUTS:
            write_key_info(work_dir, key, layout)
            # 清掉上一轮的分片 —— 残留的分片会让"这次转码成功"变得不可信
            for name in os.listdir(work_dir):
                if name.startswith("seg_") and name.endswith(".ts"):
                    os.remove(os.path.join(work_dir, name))
            if os.path.exists(m3u8_path):
                os.remove(m3u8_path)

            cmd = [
                ffmpeg_path, "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=64x64:rate=5:duration=1",
                "-c:v", "libx264", "-preset", "ultrafast", "-t", "1",
                "-hls_time", "1", "-hls_playlist_type", "vod",
                "-hls_key_info_file", key_info,
                "-hls_segment_filename", segment_pattern,
                m3u8_path,
            ]
            try:
                proc = subprocess.run(
                    cmd, cwd=work_dir, stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT, timeout=timeout,
                )
                output = proc.stdout.decode("utf-8", errors="replace")
                returncode = proc.returncode
            except FileNotFoundError:
                result["error"] = f"FFmpeg 不可执行：{ffmpeg_path}"
                return result
            except subprocess.TimeoutExpired:
                output = "timeout"
                returncode = -1
            except Exception as e:  # 其它异常（权限、崩了……）
                output = f"{type(e).__name__}: {e}"
                returncode = -1

            ok = returncode == 0 and playlist_key_uri_is_correct(m3u8_path)
            result["tried"].append({  # type: ignore[union-attr]
                "layout": layout,
                "returncode": returncode,
                "ok": ok,
                "output_tail": output[-300:],
            })
            if ok:
                result["layout"] = layout
                return result
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)

    return result


def layout_order(detected: Optional[str]) -> List[str]:
    """要试的布局顺序：探测出来的排第一。

    探测失败（None）就按默认顺序全试一遍。
    """
    if detected and detected in KEY_INFO_LAYOUTS:
        return [detected] + [x for x in KEY_INFO_LAYOUTS if x != detected]
    return list(KEY_INFO_LAYOUTS)
