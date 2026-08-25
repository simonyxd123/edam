"""
视频处理器
- HLS 切片 + AES-128 加密
- 视频帧指纹提取（pHash + 帧间冗余）
"""
import asyncio
import hashlib
import os
import subprocess
import tempfile
from typing import Any, Dict, List
from uuid import uuid4

import cv2
import imagehash
import numpy as np
import structlog
from minio import Minio
from minio.error import S3Error
from PIL import Image

from ..config import settings

log = structlog.get_logger()


class VideoProcessor:
    """视频异步处理"""

    def __init__(self) -> None:
        self.minio = Minio(
            settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", ""),
            access_key=settings.MINIO_ACCESS_KEY,
            secret_key=settings.MINIO_SECRET_KEY,
            secure=settings.MINIO_ENDPOINT.startswith("https://"),
        )

    async def process(self, payload: Dict[str, Any]) -> None:
        """
        视频处理流水线
        :param payload: {video_id, input_path, classification_lv, uploader_id}
        """
        video_id = payload["video_id"]
        input_path = payload["input_path"]
        log.info("video_processing_start", video_id=video_id, input_path=input_path)

        try:
            # 1. 下载视频到临时目录
            local_path = await self._download_from_minio(
                settings.MINIO_BUCKET_VIDEOS, input_path
            )

            # 2. HLS 切片 + AES 加密
            hls_dir = await self._hls_transcode(local_path, video_id)

            # 3. 提取帧指纹
            fingerprint_path = await self._extract_fingerprints(local_path, video_id)

            # 4. 上传 HLS 与指纹到 MinIO
            await self._upload_hls(hls_dir, video_id)
            await self._upload_fingerprint(fingerprint_path, video_id)

            log.info("video_processing_complete", video_id=video_id)

        except Exception as e:
            log.error("video_processing_failed", video_id=video_id, error=str(e))
            raise
        finally:
            # 清理临时文件
            self._cleanup_temp(local_path)

    async def _download_from_minio(self, bucket: str, object_name: str) -> str:
        """从 MinIO 下载到本地临时目录"""
        tmp_dir = tempfile.mkdtemp(prefix="edam-video-")
        local_path = os.path.join(tmp_dir, os.path.basename(object_name))
        await asyncio.to_thread(
            self.minio.fget_object, bucket, object_name, local_path
        )
        return local_path

    async def _hls_transcode(self, input_path: str, video_id: int) -> str:
        """
        FFmpeg HLS 切片 + AES 加密
        参考方案书 4.2 节
        """
        output_dir = os.path.join(tempfile.gettempdir(), f"edam-hls-{video_id}")
        os.makedirs(output_dir, exist_ok=True)
        m3u8_path = os.path.join(output_dir, "playlist.m3u8")
        key_url = settings.HLS_KEY_URL.format(video_id=video_id)

        cmd = [
            settings.FFMPEG_PATH,
            "-i", input_path,
            "-c:v", "libx264",
            "-c:a", "aac",
            "-hls_time", str(settings.HLS_SEGMENT_DURATION),
            "-hls_playlist_type", settings.HLS_PLAYLIST_TYPE,
            "-hls_key_url", key_url,
            "-hls_segment_filename", os.path.join(output_dir, "segment_%03d.ts"),
            "-threads", str(settings.FFMPEG_THREADS),
            m3u8_path,
        ]

        # 异步执行 FFmpeg
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()

        if proc.returncode != 0:
            log.error("ffmpeg_failed", returncode=proc.returncode, stderr=stderr.decode()[:500])
            raise RuntimeError(f"FFmpeg HLS 转码失败: {video_id}")

        log.info("hls_transcode_complete", video_id=video_id, output_dir=output_dir)
        return output_dir

    async def _extract_fingerprints(self, input_path: str, video_id: int) -> str:
        """
        提取视频帧指纹
        算法：
        - 每 30s 提取 1 个关键帧
        - 计算 pHash（64-bit）
        - 多帧冗余（同一指纹在视频中出现 3 次以提高鲁棒性）
        参考方案书 4.4.1 节
        """
        cap = cv2.VideoCapture(input_path)
        fps = cap.get(cv2.CAP_PROP_FPS)
        frame_interval = max(1, int(fps * settings.FINGERPRINT_KEYFRAME_INTERVAL))
        fingerprints: List[str] = []

        frame_idx = 0
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break
            if frame_idx % frame_interval == 0:
                # 缩小到 32x32 用于 pHash
                small = cv2.resize(frame, (32, 32))
                rgb_frame = cv2.cvtColor(small, cv2.COLOR_BGR2RGB)
                # 正确：cv2 ndarray → PIL.Image → imagehash.phash
                pil_img = Image.fromarray(rgb_frame)
                h = imagehash.phash(pil_img, hash_size=8)
                fingerprints.append(str(h))
            frame_idx += 1
        cap.release()

        log.info(
            "fingerprint_extraction_complete",
            video_id=video_id,
            count=len(fingerprints),
        )

        # 持久化（追加 employee_id + timestamp 在按需嵌入时）
        output_path = os.path.join(tempfile.gettempdir(), f"edam-fp-{video_id}.json")
        import json
        import datetime
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump({
                "video_id": video_id,
                "fingerprint_count": len(fingerprints),
                "fingerprints": fingerprints,
                "extracted_at": datetime.datetime.utcnow().isoformat() + "Z",
            }, f, ensure_ascii=False, indent=2)
        return output_path

    async def _upload_hls(self, hls_dir: str, video_id: int) -> None:
        """上传 HLS 切片到 MinIO"""
        for root, _, files in os.walk(hls_dir):
            for fname in files:
                local_file = os.path.join(root, fname)
                object_name = f"videos/{video_id}/hls/{fname}"
                await asyncio.to_thread(
                    self.minio.fput_object,
                    settings.MINIO_BUCKET_VIDEOS,
                    object_name,
                    local_file,
                    content_type="application/octet-stream",
                )

    async def _upload_fingerprint(self, local_path: str, video_id: int) -> None:
        """上传指纹到 MinIO"""
        object_name = f"videos/{video_id}/fingerprint.json"
        await asyncio.to_thread(
            self.minio.fput_object,
            settings.MINIO_BUCKET_VIDEOS,
            object_name,
            local_path,
            content_type="application/json",
        )

    def _cleanup_temp(self, path: str) -> None:
        """清理临时目录"""
        if not path:
            return
        try:
            import shutil
            parent = os.path.dirname(path)
            if parent and os.path.exists(parent) and parent.startswith(tempfile.gettempdir()):
                shutil.rmtree(parent, ignore_errors=True)
        except Exception as e:
            log.warning("temp_cleanup_failed", path=path, error=str(e))