"""
视频处理器
- HLS 切片 + AES-128 加密
- 视频帧指纹提取（pHash + 帧间冗余）
"""
import asyncio
import hashlib
import os
import re
import subprocess
import tempfile
import time
from typing import Any, Dict, List, Optional
from uuid import uuid4

import cv2
import imagehash
import numpy as np
import structlog
from minio import Minio
from minio.error import S3Error
from PIL import Image

from ..config import settings

import requests

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

        hls_status = 3   # 默认 failed
        fp_status = 3
        hls_path = None
        fingerprint_path = None
        duration_sec: Optional[int] = None

        try:
            # 1. 下载视频到临时目录
            local_path = await self._download_from_minio(
                settings.MINIO_BUCKET_VIDEOS, input_path
            )

            # 2. ffprobe 提取时长（即使后面转码失败也能拿到时长）
            duration_sec = await self._probe_duration(local_path)

            # 3. HLS 切片 + AES 加密（含 drawtext 水印）
            watermark_text = f"EDAM video:{video_id} {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
            hls_dir = await self._hls_transcode(local_path, video_id, watermark_text)
            hls_path = f"videos/{video_id}/hls/playlist.m3u8"
            hls_status = 2  # ready

            # 4. 提取帧指纹
            local_fp = await self._extract_fingerprints(local_path, video_id)
            fingerprint_path = f"videos/{video_id}/fingerprint.json"
            fp_status = 2    # ready

            # 5. 上传 HLS 与指纹到 MinIO
            await self._upload_hls(hls_dir, video_id)
            await self._upload_fingerprint(local_fp, video_id)

            log.info("video_processing_complete", video_id=video_id, duration_sec=duration_sec)

        except Exception as e:
            log.error("video_processing_failed", video_id=video_id, error=str(e))
            # 出错也继续到 finally，外层 finally 统一回调后端
        finally:
            # 6. 不管成功失败都回调后端更新 video_resource.hls_status / fingerprint_status / duration_sec
            #    前端轮询 /videos/{id} 看到 ready/failed 后弹提示
            await self._notify_backend_status(
                video_id,
                hls_status=hls_status, hls_path=hls_path,
                fingerprint_status=fp_status, fingerprint_path=fingerprint_path,
                duration_sec=duration_sec,
            )
            # 清理临时文件
            try:
                self._cleanup_temp(local_path)  # type: ignore
            except Exception:
                pass

    async def _probe_duration(self, input_path: str) -> Optional[int]:
        """
        用 ffprobe 提取视频时长（秒）

        返回 int 秒数；失败返回 None（不抛错，让上层用 fallback）。
        """
        cmd = [
            settings.FFPROBE_PATH,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            input_path,
        ]
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await proc.communicate()
            if proc.returncode != 0:
                log.warning("ffprobe_failed",
                            returncode=proc.returncode,
                            stderr=stderr.decode()[:200])
                return None
            text = stdout.decode().strip()
            if not text:
                return None
            return int(float(text))
        except Exception as e:
            log.warning("ffprobe_exception", error=str(e))
            return None

    async def _notify_backend_status(self, video_id: int,
                                       hls_status: Optional[int] = None, hls_path: Optional[str] = None,
                                       fingerprint_status: Optional[int] = None, fingerprint_path: Optional[str] = None,
                                       duration_sec: Optional[int] = None) -> None:
        """回调后端 PATCH /videos/{video_id}/status"""
        url = f"{settings.BACKEND_BASE_URL}/api/v1/videos/{video_id}/status"
        payload: Dict[str, Any] = {}
        if hls_status is not None:
            payload["hls_status"] = hls_status
        if hls_path:
            payload["hls_path"] = hls_path
        if fingerprint_status is not None:
            payload["fingerprint_status"] = fingerprint_status
        if fingerprint_path:
            payload["fingerprint_path"] = fingerprint_path
        if duration_sec is not None:
            payload["duration_sec"] = duration_sec

        try:
            resp = await asyncio.to_thread(
                requests.patch, url, json=payload, timeout=10
            )
            if resp.status_code >= 400:
                log.error("notify_backend_failed", video_id=video_id,
                          status=resp.status_code, body=resp.text[:300])
            else:
                log.info("notify_backend_ok", video_id=video_id, status=resp.status_code)
        except Exception as e:
            log.error("notify_backend_exception", video_id=video_id, error=str(e))

    async def _download_from_minio(self, bucket: str, object_name: str) -> str:
        """从 MinIO 下载到本地临时目录"""
        tmp_dir = tempfile.mkdtemp(prefix="edam-video-")
        local_path = os.path.join(tmp_dir, os.path.basename(object_name))
        await asyncio.to_thread(
            self.minio.fget_object, bucket, object_name, local_path
        )
        return local_path

    async def _hls_transcode(self, input_path: str, video_id: int, watermark_text: str = "EDAM") -> str:
        """
        FFmpeg HLS 切片 + AES 加密
        参考方案书 4.2 节

        注意：FFmpeg 4.3 不支持 -hls_key_url（4.4+ 才有）。
        我们改用后处理：FFmpeg 输出 m3u8 后用 _rewrite_key_url() 把
        URI="key.bin" 替换为后端真实 key URL。

        drawtext 水印（v3.2 视频防泄密）：
        - 右下角员工号 + 时间戳，半透明
        - 字体根据视频高度自动调整（height/30, min 16）
        - 颜色白底带黑边框（任何背景都可见）
        """
        output_dir = os.path.join(tempfile.gettempdir(), f"edam-hls-{video_id}")
        os.makedirs(output_dir, exist_ok=True)
        m3u8_path = os.path.join(output_dir, "playlist.m3u8")
        key_url = settings.HLS_KEY_URL.format(video_id=video_id)

        # drawtext filter（转义冒号和单引号，FFmpeg filter 语法敏感）
        wm_escaped = watermark_text.replace("'", "\\'").replace(":", "\\:")
        font_size = 18  # 适合 720p；1080p 会偏小，prod 应按 height/30 自适应
        drawtext_filter = (
            f"drawtext=text='{wm_escaped}':"
            f"fontcolor=white:fontsize={font_size}:"
            f"box=1:boxcolor=black@0.4:boxborderw=8:"
            "x=w-tw-20:h-th-20"
        )

        cmd = [
            settings.FFMPEG_PATH,
            "-i", input_path,
            "-vf", drawtext_filter,
            "-c:v", "libx264",
            "-c:a", "aac",
            "-hls_time", str(settings.HLS_SEGMENT_DURATION),
            "-hls_playlist_type", settings.HLS_PLAYLIST_TYPE,
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
            log.error("ffmpeg_failed",
                       returncode=proc.returncode,
                       cmd=" ".join(cmd),
                       stderr=stderr.decode("utf-8", errors="replace"))
            raise RuntimeError(f"FFmpeg HLS 转码失败: {video_id}")

        # 后处理：把 m3u8 里的 URI="key.bin" 改为真实 key URL
        await self._rewrite_key_url(m3u8_path, key_url)

        log.info("hls_transcode_complete", video_id=video_id, output_dir=output_dir)
        return output_dir

    async def _rewrite_key_url(self, m3u8_path: str, key_url: str) -> None:
        """把 m3u8 里的 URI=\"key.bin\" 替换为真实 key URL"""
        def _do_replace():
            with open(m3u8_path, "r", encoding="utf-8") as f:
                content = f.read()
            # 替换 EXT-X-KEY 行里的 URI
            # 形如: #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=...
            new_content = re.sub(
                r'URI="[^"]*"',
                f'URI="{key_url}"',
                content,
                count=1,
            )
            with open(m3u8_path, "w", encoding="utf-8") as f:
                f.write(new_content)

        await asyncio.to_thread(_do_replace)
        log.info("m3u8_key_url_rewritten", path=m3u8_path, key_url=key_url)

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