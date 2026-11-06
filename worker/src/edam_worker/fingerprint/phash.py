"""
pHash 帧指纹计算 —— **全仓库唯一实现**（v3.4.1）

**为什么单独抽一个文件**：此前有两套 pHash 实现，且**互不兼容**：

- `processors/video.py`：imagehash 库（DCT + 缩放 + **高通滤波**），
  产出 `videos/{id}/fingerprint.json`
- `fingerprint/PHashService.py`：自己手写 DCT（**没有高通滤波**，
  中位数的取法也不同）

两边算出的 bit 串对不上 ⇒ 汉明距离比较毫无意义，匹配率 ≈ 0，
而且**不报任何错** —— 看起来跑通了，只是永远匹配不上。

现在两边都调这里，产出的 8 字节**逐位相同**。

算法（imagehash.phash，Zauner 2010 的感知哈希变体）：
1. 帧缩放到 32x32（BGR → RGB）
2. imagehash 内部：转灰度 → 高通滤波（DCT 低频 - 高频）→ 取 8x8 低频
3. 与中位数比较得 64 bit
4. 8 字节 = 64 bit

**格式约定**：`fingerprint.json` 里存的是 16 位小写 hex 串（= 8 字节）。
这个格式是已落盘的数据，**不要改** —— 改了历史指纹就全部失效。
"""
import cv2
import imagehash
import numpy as np
from PIL import Image

# pHash 的字节长度（64 bit）
HASH_SIZE = 8

# 缩放目标尺寸（imagehash 的 hash_size * highfreq_factor = 8 * 4）
_RESIZE_TO = 32


def compute_frame_phash(frame: np.ndarray) -> bytes:
    """计算单帧 pHash（8 字节 = 64 bit）。

    :param frame: OpenCV 帧（BGR）
    :return: 8 字节 pHash
    """
    if frame is None or getattr(frame, "size", 0) == 0:
        raise ValueError("Empty frame")

    # 与 processors/video.py 的历史实现**逐位一致**：
    # resize(32,32) → BGR2RGB → PIL → imagehash.phash(hash_size=8)
    small = cv2.resize(frame, (_RESIZE_TO, _RESIZE_TO))
    rgb_frame = cv2.cvtColor(small, cv2.COLOR_BGR2RGB)
    pil_img = Image.fromarray(rgb_frame)
    h = imagehash.phash(pil_img, hash_size=HASH_SIZE)
    return bytes.fromhex(str(h))


def phash_to_hex(phash: bytes) -> str:
    """8 字节 pHash → 16 位小写 hex 串（`fingerprint.json` 的存储格式）。"""
    return phash.hex()


def hex_to_phash(hex_str: str) -> bytes:
    """16 位 hex 串 → 8 字节 pHash。

    兼容不带 `0x` 前缀的写法（有些工具会这么写）。
    """
    s = (hex_str or "").strip().lower()
    if s.startswith("0x"):
        s = s[2:]
    if len(s) != HASH_SIZE * 2:
        raise ValueError(
            f"pHash hex 长度应为 {HASH_SIZE * 2}，实际 {len(s)}: {hex_str!r}"
        )
    return bytes.fromhex(s)


def hamming_distance(hash1: bytes, hash2: bytes) -> int:
    """两个 pHash 的汉明距离。"""
    if len(hash1) != len(hash2):
        raise ValueError("Hash length mismatch")
    return sum(bin(b1 ^ b2).count("1") for b1, b2 in zip(hash1, hash2))


def similarity_score(hash1: bytes, hash2: bytes) -> float:
    """相似度（0-1，1 = 完全一致）。"""
    distance = hamming_distance(hash1, hash2)
    return 1.0 - (distance / (HASH_SIZE * HASH_SIZE))
