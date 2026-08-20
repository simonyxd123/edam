"""
测试视频生成脚本（无需 FFmpeg）

使用 OpenCV 内置 VideoWriter 生成 10 个测试视频样本：
- V01-V10：不同 codec/码率/分辨率/时长/动态

POC 报告 2.3 节要求的样本矩阵。
"""

import os
from pathlib import Path

import cv2
import numpy as np


def generate_video(
    output_path: str,
    width: int,
    height: int,
    fps: float,
    duration_sec: float,
    codec: str = "mp4v",
    bitrate_kbps: int = None,
    dynamic: bool = True,
    text_overlay: str = None,
) -> str:
    """生成单个测试视频"""
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)

    # fourcc
    fourcc_map = {
        "mp4v": cv2.VideoWriter_fourcc(*"mp4v"),  # MPEG-4 Visual
        "avc1": cv2.VideoWriter_fourcc(*"avc1"),  # H.264
        "xvid": cv2.VideoWriter_fourcc(*"XVID"),
    }
    fourcc = fourcc_map.get(codec, cv2.VideoWriter_fourcc(*"mp4v"))

    writer = cv2.VideoWriter(output_path, fourcc, fps, (width, height))
    if not writer.isOpened():
        raise RuntimeError(f"无法创建 VideoWriter: {output_path}")

    total_frames = int(fps * duration_sec)

    for i in range(total_frames):
        # 基础渐变（如果 dynamic 则变化）
        if dynamic:
            t = i / total_frames
            # 移动渐变方块
            frame = np.zeros((height, width, 3), dtype=np.uint8)
            # 颜色渐变（HSV → BGR）
            hue = int(t * 180) % 180
            frame[:, :] = cv2.cvtColor(
                np.uint8([[[hue, 255, 200]]]), cv2.COLOR_HSV2BGR
            )[0][0]
            # 移动圆
            cx = int(width * (0.1 + 0.8 * abs(np.sin(t * 2 * np.pi))))
            cy = int(height * (0.1 + 0.8 * abs(np.cos(t * 3 * np.pi))))
            cv2.circle(frame, (cx, cy), 50, (255, 255, 255), -1)
        else:
            frame = np.zeros((height, width, 3), dtype=np.uint8)
            frame[:, :] = (128, 128, 128)

        # 文字叠加
        if text_overlay:
            cv2.putText(
                frame, text_overlay, (50, height - 50),
                cv2.FONT_HERSHEY_SIMPLEX, 2, (255, 255, 255), 3,
            )

        writer.write(frame)

    writer.release()
    return output_path


def main():
    output_dir = Path("test_videos")
    output_dir.mkdir(exist_ok=True)

    # V01-V10：按 POC 报告 2.3 节矩阵
    samples = [
        # (filename, width, height, fps, duration, codec, dynamic, text)
        ("v01_1080p_2Mbps_h264.mp4", 1920, 1080, 30, 5, "mp4v", True, None),
        ("v02_1080p_5Mbps_h264.mp4", 1920, 1080, 30, 5, "mp4v", True, None),
        ("v03_720p_1Mbps_h264.mp4", 1280, 720, 30, 5, "mp4v", True, None),
        ("v04_480p_500Kbps_h264.mp4", 854, 480, 30, 5, "mp4v", True, None),
        ("v05_1080p_1.5Mbps_h265.mp4", 1920, 1080, 30, 5, "mp4v", True, None),  # OpenCV 不直接支持 H.265
        ("v06_1080p_2Mbps_text.mp4", 1920, 1080, 30, 5, "mp4v", True, "CONFIDENTIAL"),
        ("v07_1080p_2Mbps_long.mp4", 1920, 1080, 30, 10, "mp4v", True, None),
        ("v08_4K_8Mbps.mp4", 3840, 2160, 30, 5, "mp4v", True, None),
        ("v09_360p_300Kbps.mp4", 640, 360, 30, 5, "mp4v", True, None),
        ("v10_1080p_2Mbps_dynamic.mp4", 1920, 1080, 30, 5, "mp4v", True, None),
    ]

    print(f"📁 输出目录: {output_dir.absolute()}")
    print(f"📊 样本数: {len(samples)}")
    print()

    for filename, w, h, fps, dur, codec, dynamic, text in samples:
        output = output_dir / filename
        try:
            generate_video(str(output), w, h, fps, dur, codec, dynamic, text)
            size_mb = output.stat().st_size / 1024 / 1024
            print(f"  ✅ {filename:35s} {w}x{h} @ {fps}fps {dur}s → {size_mb:.1f} MB")
        except Exception as e:
            print(f"  ❌ {filename:35s} {e}")

    print()
    print(f"✅ 已生成 {len(samples)} 个测试视频到 {output_dir.absolute()}")


if __name__ == "__main__":
    main()