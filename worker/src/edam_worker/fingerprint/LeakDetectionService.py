"""
泄露检测服务（v3.3 W-6.3）

核心功能：
- 接收疑似泄露视频
- 逐帧计算 pHash
- 与指纹库匹配（多帧投票）
- 命中阈值告警
"""
import cv2
import numpy as np
import logging
import time
import uuid
from typing import List, Dict, Optional, Tuple
from dataclasses import dataclass, field
from datetime import datetime

from .PHashService import phash_service, FrameFingerprint

logger = logging.getLogger(__name__)


@dataclass
class DetectionMatch:
    """单帧匹配结果"""
    user_id: int
    session_id: str
    frame_index: int
    hamming_distance: int
    similarity: float


@dataclass
class DetectionResult:
    """整体检测结果"""
    detection_id: str
    resource_type: str           # video / document
    leaked_file_path: str
    status: str                  # pending / confirmed / dismissed
    matches: List[DetectionMatch] = field(default_factory=list)
    matched_user_id: Optional[int] = None
    matched_session_id: Optional[str] = None
    best_match_score: float = 0.0
    matched_frames: int = 0
    total_frames: int = 0
    duration_ms: int = 0

    @property
    def is_leaked(self) -> bool:
        """多帧投票阈值：≥ 3 帧匹配 + 平均相似度 > 80%"""
        if len(self.matches) < 3:
            return False
        avg_similarity = sum(m.similarity for m in self.matches) / len(self.matches)
        return avg_similarity > 0.80


class LeakDetectionService:
    """
    泄露检测服务（v3.3 W-6.3）

    流程：
    1. 接收疑似泄露文件路径
    2. 抽帧 + 计算 pHash（每视频 30 帧）
    3. 与指纹库匹配（汉明距离 ≤ 10）
    4. 多帧投票（≥ 3 帧相似 → 命中）
    5. 输出命中用户 + 会话 ID + 置信度
    """

    HAMMING_THRESHOLD = 10    # 单帧匹配阈值
    MIN_MATCH_FRAMES = 3      # 多帧投票阈值
    SIMILARITY_THRESHOLD = 0.80  # 平均相似度阈值

    def detect_from_video(
        self,
        leaked_video_path: str,
        fingerprint_db: List[FrameFingerprint],
        frame_sample_count: int = 30
    ) -> DetectionResult:
        """
        检测疑似泄露视频

        Args:
            leaked_video_path: 疑似泄露视频路径
            fingerprint_db: 已知指纹库（DB 查询结果）
            frame_sample_count: 抽样帧数
        """
        start = time.time()
        detection_id = uuid.uuid4().hex
        result = DetectionResult(
            detection_id=detection_id,
            resource_type="video",
            leaked_file_path=leaked_video_path,
            status="pending",
            total_frames=frame_sample_count,
        )

        # 1. 抽样帧 + 计算 pHash
        cap = cv2.VideoCapture(leaked_video_path)
        if not cap.isOpened():
            logger.error("leak_detection_open_failed path=%s", leaked_video_path)
            return result

        total_frames_in_video = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if total_frames_in_video <= 0:
            cap.release()
            return result

        step = max(1, total_frames_in_video // frame_sample_count)
        leaked_hashes = []

        for i in range(0, total_frames_in_video, step):
            if len(leaked_hashes) >= frame_sample_count:
                break
            cap.set(cv2.CAP_PROP_POS_FRAMES, i)
            ret, frame = cap.read()
            if not ret:
                continue
            phash = phash_service.compute_frame_phash(frame)
            leaked_hashes.append(phash)

        cap.release()
        result.total_frames = len(leaked_hashes)

        # 2. 与指纹库匹配
        for leaked_hash in leaked_hashes:
            for fp in fingerprint_db:
                dist = phash_service.hamming_distance(leaked_hash, fp.phash)
                if dist <= self.HAMMING_THRESHOLD:
                    similarity = phash_service.similarity_score(leaked_hash, fp.phash)
                    result.matches.append(DetectionMatch(
                        user_id=fp.user_id,
                        session_id=fp.session_id,
                        frame_index=fp.frame_index,
                        hamming_distance=dist,
                        similarity=similarity,
                    ))

        # 3. 多帧投票
        if result.matches:
            # 按 user_id + session_id 分组
            grouped: Dict[Tuple[int, str], List[DetectionMatch]] = {}
            for m in result.matches:
                key = (m.user_id, m.session_id)
                grouped.setdefault(key, []).append(m)

            # 取帧数最多且平均相似度最高的组
            best_key = None
            best_score = 0.0
            best_count = 0
            for key, matches in grouped.items():
                if len(matches) >= self.MIN_MATCH_FRAMES:
                    avg_sim = sum(m.similarity for m in matches) / len(matches)
                    score = avg_sim * len(matches)
                    if score > best_score:
                        best_score = score
                        best_key = key
                        best_count = len(matches)

            if best_key:
                result.matched_user_id = best_key[0]
                result.matched_session_id = best_key[1]
                result.matched_frames = best_count
                result.best_match_score = best_score / best_count if best_count else 0

                if result.is_leaked:
                    result.status = "confirmed"
                    logger.warn(
                        "leak_detected detection_id=%s user_id=%d session=%s score=%.2f frames=%d",
                        detection_id, result.matched_user_id, result.matched_session_id,
                        result.best_match_score, result.matched_frames
                    )

        result.duration_ms = int((time.time() - start) * 1000)
        logger.info(
            "leak_detection_done detection_id=%s leaked=%s status=%s matches=%d ms=%d",
            detection_id, result.is_leaked, result.status,
            len(result.matches), result.duration_ms
        )
        return result


# 单例
leak_detection_service = LeakDetectionService()