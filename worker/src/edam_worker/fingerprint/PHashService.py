"""
pHash 帧指纹计算服务（v3.3 W-6.1）

按视频 POC 决策：每视频 30+ 帧指纹，多帧投票容错

**v3.4.1：算法已与 `processors/video.py` 统一。**

此前本模块自己手写 DCT（**没有高通滤波**，中位数取法也不同），
而 `processors/video.py` 用 imagehash 库 —— 两边算出的 bit 串对不上，
汉明距离比较毫无意义，匹配率 ≈ 0，而且**不报任何错**。
现在两边都调 `fingerprint/phash.py`（唯一实现），产出**逐位相同**的 8 字节。

本模块提供的是**面向泄露检测的封装**（带 user_id / session_id 的帧指纹列表），
底层算法在 `phash.py`。

接线状态：算子能力已可用（`POST /ops/leak-detect`），
但"按观看用户入库指纹 + 自动触发比对 + 告警回后端"三段链路尚未接线 ——
详见 README 的「已知限制」。
"""
import cv2
import numpy as np
import logging
from typing import List
from dataclasses import dataclass

from . import phash as _phash

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

    委托给 `fingerprint/phash.py` —— 全仓库唯一的 pHash 实现，
    与 `processors/video.py` 写进 `fingerprint.json` 的指纹**逐位相同**。

    抗压缩、亮度、几何变换、小幅旋转鲁棒
    """

    HASH_SIZE = _phash.HASH_SIZE
    FRAME_SAMPLE_COUNT = 30  # 每视频采样帧数

    def compute_frame_phash(self, frame: np.ndarray) -> bytes:
        """计算单帧 pHash（64 bit）"""
        return _phash.compute_frame_phash(frame)

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
        return _phash.hamming_distance(hash1, hash2)

    def is_similar(self, hash1: bytes, hash2: bytes, threshold: int = 10) -> bool:
        """判断两个 pHash 是否相似（汉明距离 ≤ 阈值）"""
        return self.hamming_distance(hash1, hash2) <= threshold

    def similarity_score(self, hash1: bytes, hash2: bytes) -> float:
        """计算相似度（0-1，1 = 完全一致）"""
        return _phash.similarity_score(hash1, hash2)

# 单例（供其他模块使用）
phash_service = PHashService()