"""
drawtext 水印的字体候选列表

**为什么单独一个模块**：字体自检（worker 启动时）和真正转码时选字体
必须用**同一份**候选列表 —— 两边不一致的话，自检说"有字体"、
转码时却找不到，排查方向就整个跑偏。

drawtext 的 fontfile 必须显式指定：容器/服务器上没装字体时，
``font='DejaVu Sans'`` 会依赖 fontconfig，缺失时 drawtext 直接失败
（而不是静默跳过）—— 那就等于"转码全军覆没"。
"""
import os
from typing import List, Optional

# 按优先级排列的候选（Bold 字重更醒目，且更常见）
FONT_CANDIDATES: List[str] = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
]


def find_font(candidates: Optional[List[str]] = None) -> Optional[str]:
    """返回第一个存在的字体文件路径；一个都没有则返回 None。"""
    for path in (candidates if candidates is not None else FONT_CANDIDATES):
        if os.path.exists(path):
            return path
    return None
