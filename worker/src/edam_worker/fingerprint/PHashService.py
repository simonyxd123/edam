"""
pHash 帧指纹计算服务（v3.3 W-6.1）

按视频 POC 决策：每视频 30+ 帧指纹，多帧投票容错
"""
import cv2
import numpy as np
import logging
import hashlib
from typing import List, Tuple, Optional
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class FrameFingerprint:
    """单帧指纹"""
    frame_index: int
    timestamp_sec: float
    phash: bytes       # 8 字节 = 64 bit
    user_id: int
    video_id: int
    session_id: str


class PHashService:
    """
    pHash 计算与存储

    算法（Zauner 2010）：
    1. 帧缩放至 32x32
    2. 计算 DCT
    3. 取左上 8x8 低频子带
    4. 计算中位数（排除第一个 DC 分量）
    5. 大于中位数 → 1，否则 → 0
    6. 8x8 = 64 bit hash

    抗压缩、亮度、几何变换、小幅旋转鲁棒
    """

    HASH_SIZE = 8
    FRAME_SAMPLE_COUNT = 30  # 每视频采样帧数

    def compute_frame_phash(self, frame: np.ndarray) -> bytes:
        """计算单帧 pHash（64 bit）"""
        if frame is None or frame.size == 0:
            raise ValueError("Empty frame")

        # 转灰度
        if len(frame.shape) == 3:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        else:
            gray = frame

        # 缩放至 32x32
        resized = cv2.resize(gray, (32, 32))

        # DCT
        dct = cv2.dct(np.float32(resized))

        # 取左上 8x8（低频）
        dct_low = dct[:self.HASH_SIZE, :self.HASH_SIZE]

        # 中位数（排除 DC）
        dct_no_dc = dct_low.flatten()[1:]
        median = np.median(dct_no_dc)

        # 大于中位数 → 1
        hash_bits = (dct_low > median).flatten().astype(np.uint8)
        return hash_bits.tobytes()

    def compute_video_fingerprints(
        self,
        video_path: str,
        user_id: int,
        video_id: int,
        session_id: str,
        frame_count: int = 30
    ) -> List[FrameFingerprint]:
        """计算视频的多个帧指纹

        Args:
            video_path: 视频文件路径
            user_id: 观看用户 ID
            video_id: 视频 ID
            session_id: 会话 ID
            frame_count: 采样帧数（默认 30）
        """
        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            raise RuntimeError(f"无法打开视频: {video_path}")

        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = cap.get(cv2.CAP_PROP_FPS)

        if total_frames <= 0:
            cap.release()
            raise RuntimeError(f"视频无帧: {video_path}")

        # 等间距采样 frame_count 帧
        step = max(1, total_frames // frame_count)
        fingerprints = []

        for i in range(0, total_frames, step):
            if len(fingerprints) >= frame_count:
                break
            cap.set(cv2.CAP_PROP_POS_FRAMES, i)
            ret, frame = cap.read()
            if not ret:
                continue

            phash = self.compute_frame_phash(frame)
            timestamp_sec = i / fps if fps > 0 else 0

            fingerprints.append(FrameFingerprint(
                frame_index=i,
                timestamp_sec=round(timestamp_sec, 2),
                phash=phash,
                user_id=user_id,
                video_id=video_id,
                session_id=session_id,
            ))

        cap.release()

        logger.info(
            "phash_computed video_id=%d user_id=%d fps=%.1f frames=%d/%d",
            video_id, user_id, fps, len(fingerprints), total_frames
        )
        return fingerprints

    def hamming_distance(self, hash1: bytes, hash2: bytes) -> int:
        """计算两个 pHash 的汉明距离"""
        if len(hash1) != len(hash2):
            raise ValueError("Hash length mismatch")
        return sum(bin(b1 ^ b2).count('1') for b1, b2 in zip(hash1, hash2))

    def is_similar(self, hash1: bytes, hash2: bytes, threshold: int = 10) -> bool:
        """判断两个 pHash 是否相似（汉明距离 ≤ 阈值）"""
        return self.hamming_distance(hash1, hash2) <= threshold

    def similarity_score(self, hash1: bytes, hash2: bytes) -> float:
        """计算相似度（0-1，1 = 完全一致）"""
        distance = self.hamming_distance(hash1, hash2)
        return 1.0 - (distance / (self.HASH_SIZE * self.HASH_SIZE))


# 单例（供其他模块使用）
phash_service = PHashService()