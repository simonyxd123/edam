"""
视频处理器
- HLS 切片 + AES-128 加密
- 视频帧指纹提取（pHash + 帧间冗余）
"""
import asyncio
import datetime
import io
import json
import os
import tempfile
from typing import Any, Dict, List, Optional

import cv2
import imagehash
import structlog
from minio import Minio
from PIL import Image

from ..config import settings
from ..errors import PermanentWorkerError

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
        :param payload: {video_id, input_path, classification_lv, uploader_id, employee_no}
        """
        video_id = payload["video_id"]
        input_path = payload["input_path"]
        employee_no = payload.get("employee_no") or f"U{video_id}"
        log.info("video_processing_start", video_id=video_id, input_path=input_path, employee_no=employee_no)

        hls_status = 3   # 默认 failed
        fp_status = 3
        hls_path = None
        fingerprint_path = None
        duration_sec: Optional[int] = None
        # 初始化为 None —— 下面 finally 会引用它；
        # 若下载阶段就抛异常，变量未定义会在 finally 里抛 NameError，
        # 把真正的原始异常顶替掉（排障时极具误导性）
        local_path: Optional[str] = None
        work_dir: Optional[str] = None

        failed = False
        try:
            # 1. 下载视频到工作目录
            work_dir = tempfile.mkdtemp(prefix=f"edam-video-{video_id}-")
            local_path = await self._download_from_minio(
                settings.MINIO_BUCKET_VIDEOS, input_path, work_dir
            )

            # 2. ffprobe 提取时长（即使后面转码失败也能拿到时长）
            duration_sec = await self._probe_duration(local_path)

            # 3. HLS 切片 + AES-128 加密（含 drawtext 水印）
            watermark_text = f"EDAM {employee_no} video:{video_id} {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
            hls_dir, key_bytes = await self._hls_transcode(local_path, video_id, watermark_text, work_dir)

            # 4. 提取帧指纹
            local_fp = await self._extract_fingerprints(local_path, video_id, work_dir)
            fingerprint_path = f"videos/{video_id}/fingerprint.json"

            # 5. 上传 HLS 与指纹到 MinIO
            #    注意：hls_status / fp_status 必须在上传**成功之后**才置 ready。
            #    否则上传到一半失败时，MinIO 上只有半套分片，
            #    后端却收到 "ready" —— 前端提示"处理完成"，点进去却是 404。
            await self._upload_hls(hls_dir, video_id)
            hls_path = f"videos/{video_id}/hls/playlist.m3u8"
            hls_status = 2  # ready

            await self._upload_fingerprint(local_fp, video_id)
            fp_status = 2    # ready

            # 6. AES 密钥单独存到 hls/ 之外，避免被 segment 代理误暴露
            await self._upload_key(key_bytes, video_id)

            log.info("video_processing_complete", video_id=video_id, duration_sec=duration_sec)

        except Exception as e:
            failed = True
            log.error("video_processing_failed", video_id=video_id, error=str(e))
            # 必须 raise —— 外层 _process_with_retry 靠异常触发重试。
            # 吞掉的话任何瞬时故障（MinIO 抖动 / FFmpeg 偶发失败）都会
            # 让消息被直接 ACK 丢弃、DB 永久停在 failed，
            # 而 Prometheus 还会显示 100% 成功率。
            # 状态回调由 finally 负责，与是否重抛不冲突。
            raise
        finally:
            # 7. 不管成功失败都回调后端更新 video_resource.hls_status / fingerprint_status / duration_sec
            #    前端轮询 /videos/{id} 看到 ready/failed 后弹提示
            try:
                await self._notify_backend_status(
                    video_id,
                    hls_status=hls_status, hls_path=hls_path,
                    fingerprint_status=fp_status, fingerprint_path=fingerprint_path,
                    duration_sec=duration_sec,
                )
            except Exception as cb_err:
                if failed:
                    # 转码本身已经失败了 —— 回调失败**不能顶替**原始异常，
                    # 否则排障时看到的是回调错误，真正的转码错误被吞了
                    log.error("notify_backend_failed_after_processing_error",
                              video_id=video_id, error_type=type(cb_err).__name__,
                              error=str(cb_err)[:300])
                else:
                    # 转码成功、只是回调失败 —— 这**同样是失败**：
                    # 后端不知道结果 ⇒ hls_status 永远停在 pending
                    # ⇒ 用户点播放还是 404。必须冒泡出去让任务被记成失败。
                    raise
            finally:
                # 临时文件一定要清（回调抛异常也不能跳过，否则 /tmp 会被撑爆）
                self._cleanup_temp(work_dir)

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
        """回调后端 PATCH /videos/{video_id}/status

        **失败必须 raise。** 回调失败 = 后端不知道这次转码的结果
        ⇒ video_resource.hls_status 永远停在 pending
        ⇒ 用户点播放还是 404（2026-10-28 那次故障的形态）。

        以前这里只 log 不抛，任务照样被记成"成功"、消息照样被 ACK，
        Prometheus 显示 100% 成功率 —— 而后端什么都没收到。**静默失败**。

        失败分两类，处理方式不同：
        - **配置问题**（401/403）：重试多少次都没用 → PermanentWorkerError，
          不重试整个任务（省下 3 倍转码开销），但必须记成失败并喊出来；
        - **瞬时故障**（连不上 / 5xx）：先在回调层便宜地重试几次，
          仍不行才把整个任务交给外层重试。
        """
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

        # 带 X-Worker-Token —— 后端 PATCH /videos/{id}/status 已改为要求该头
        # （原先 permitAll，谁都能把没转码的视频标成 ready）
        headers = {}
        if settings.WORKER_TOKEN:
            headers["X-Worker-Token"] = settings.WORKER_TOKEN
        else:
            log.warning(
                "worker_token_not_configured",
                video_id=video_id,
                hint=("后端要求 X-Worker-Token（fail-closed）。不配的话回调一律 401，"
                      "转码完成了 hls_status 也不会变 —— 用户点播放还是 404"),
            )

        max_attempts = max(1, settings.WORKER_CALLBACK_MAX_ATTEMPTS)
        backoff = max(0.0, settings.WORKER_CALLBACK_RETRY_BACKOFF_SEC)
        last_error = "unknown"

        for attempt in range(1, max_attempts + 1):
            try:
                resp = await asyncio.to_thread(
                    requests.patch, url, json=payload, headers=headers, timeout=10
                )
            except Exception as e:
                # 网络层失败（后端没起来 / 连接被重置）—— 瞬时，值得重试
                last_error = f"{type(e).__name__}: {e}"
                log.warning("notify_backend_exception", video_id=video_id,
                            attempt=attempt, max_attempts=max_attempts, error=last_error)
            else:
                if resp.status_code < 400:
                    log.info("notify_backend_ok", video_id=video_id, status=resp.status_code)
                    return
                if resp.status_code in (401, 403):
                    # 配置问题 —— 重试没有意义，直接永久失败并说清楚原因
                    raise PermanentWorkerError(
                        f"后端拒绝了回调（HTTP {resp.status_code}）：worker 的 WORKER_TOKEN "
                        f"与后端的 edam.worker.token 不一致，或有一边没配。"
                        f"（后端日志会写明具体是哪种）")
                if resp.status_code < 500:
                    # 其它 4xx：请求本身有问题（路径错了 / body 不合法），重试也没用
                    raise PermanentWorkerError(
                        f"后端拒绝了回调（HTTP {resp.status_code}）：{resp.text[:200]}")
                # 5xx：服务端瞬时故障，值得重试
                last_error = f"HTTP {resp.status_code}: {resp.text[:200]}"
                log.warning("notify_backend_failed", video_id=video_id,
                            attempt=attempt, max_attempts=max_attempts,
                            status=resp.status_code, body=resp.text[:300])

            if attempt < max_attempts:
                await asyncio.sleep(backoff * (2 ** (attempt - 1)))

        # 瞬时故障重试到头 —— 抛普通异常，交给外层把整个任务重跑一遍
        # （转码虽然贵，但"后端没收到状态"更贵）
        raise RuntimeError(f"回调后端失败（已重试 {max_attempts} 次）：{last_error}")

    async def _download_from_minio(self, bucket: str, object_name: str, work_dir: str) -> str:
        """从 MinIO 下载到工作目录"""
        local_path = os.path.join(work_dir, os.path.basename(object_name))
        await asyncio.to_thread(
            self.minio.fget_object, bucket, object_name, local_path
        )
        return local_path

    async def _hls_transcode(
        self,
        input_path: str,
        video_id: int,
        watermark_text: str = "EDAM",
        work_dir: Optional[str] = None,
    ) -> tuple[str, bytes]:
        """
        FFmpeg HLS 切片 + AES-128 加密
        参考方案书 4.2 节

        加密（关键）：
        - 生成 16 字节随机 key，写入 key.info（``URI key.bin`` + hex key）
        - FFmpeg 用 ``-hls_key_info_file`` 做 AES-128-CBC 加密，
          每个分片用独立 IV（key.info 不写 IV 时 FFmpeg 自动生成）
        - m3u8 里的 ``URI="key.bin"`` 保持相对路径，
          由后端 PlaybackController 在输出时改写成带 token 的绝对 URL
          （与 segment 改写同一套机制，避免两处逻辑不一致）

        注意：FFmpeg 4.3 不支持 ``-hls_key_url``（4.4+ 才有），
        所以走 ``-hls_key_info_file`` 这条兼容路径。

        drawtext 水印（v3.2 视频防泄密）：
        - 右下角员工号 + 时间戳，半透明
        - 字体根据视频高度自动调整（height/30, min 16）
        - 颜色白底带黑边框（任何背景都可见）

        :return: (hls 输出目录, 16 字节 AES key)
        """
        if work_dir is None:
            work_dir = tempfile.mkdtemp(prefix=f"edam-hls-{video_id}-")
        # mkdtemp 保证每次转码都是干净目录 —— 复用固定名字会让
        # 上一轮残留的分片被一并上传（MinIO 存储膨胀 + 运维指标失真）
        output_dir = tempfile.mkdtemp(prefix="hls-", dir=work_dir)
        m3u8_path = os.path.join(output_dir, "playlist.m3u8")
        key_path = os.path.join(output_dir, "key.bin")

        # --- 生成 AES-128 key（16 字节）与 key.info ---
        key = os.urandom(16)
        await asyncio.to_thread(self._write_key_info, output_dir, key)

        # drawtext filter（转义冒号和单引号，FFmpeg filter 语法敏感）
        wm_escaped = watermark_text.replace("'", "\\'").replace(":", "\\:")
        # 小字 + 白色 + 半透明背景 → 不挡视频字幕，视野边缘低调
        font_size = 20  # 720p 高度 720 → 字号 20 ≈ 2.8%

        # 显式指定 fontfile（容器没装字体时 drawtext 会静默失败）
        font_candidates = [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        ]
        font_path = None
        for p in font_candidates:
            if os.path.exists(p):
                font_path = p
                break
        if font_path:
            font_part = f"fontfile='{font_path}':"
        else:
            font_part = "font='DejaVu Sans':"
            log.warning("drawtext_font_not_found", candidates=font_candidates)

        # 右下角（不挡字幕）+ 白字 + 半透明黑底保证可读 + 低调不抢眼
        drawtext_filter = (
            f"drawtext=text='{wm_escaped}':"
            f"{font_part}"
            f"fontcolor=white@0.75:fontsize={font_size}:"
            f"box=1:boxcolor=black@0.35:boxborderw=4:"
            "x=w-tw-12:y=h-th-12"
        )

        cmd = [
            settings.FFMPEG_PATH,
            "-i", input_path,
            "-vf", drawtext_filter,
            "-c:v", "libx264",
            "-c:a", "aac",
            "-hls_time", str(settings.HLS_SEGMENT_DURATION),
            "-hls_playlist_type", settings.HLS_PLAYLIST_TYPE,
            "-hls_key_info_file", os.path.join(output_dir, "key.info"),
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

        # 校验：加密确实生效了（m3u8 必须有 EXT-X-KEY 行）
        await self._assert_encrypted(m3u8_path)

        # key.bin 留在 output_dir 里，随 _upload_hls 一起上传到
        # videos/{id}/hls/key.bin；后端另有一份独立副本在
        # videos/{id}/key.bin（不在 hls/ 目录下，避免被 segment 代理暴露）
        with open(key_path, "wb") as f:
            f.write(key)

        log.info("hls_transcode_complete", video_id=video_id, output_dir=output_dir)
        return output_dir, key

    def _write_key_info(self, output_dir: str, key: bytes) -> None:
        """写 FFmpeg 的 key.info：URI 指向同目录的 key.bin，key 为 hex。

        不写 IV —— 让 FFmpeg 为每个分片生成独立 IV（HLS AES-128 规范做法）。
        """
        key_info_path = os.path.join(output_dir, "key.info")
        with open(key_info_path, "w", encoding="utf-8", newline="\n") as f:
            f.write("URI key.bin\n")
            f.write(key.hex() + "\n")

    async def _assert_encrypted(self, m3u8_path: str) -> None:
        """确认 m3u8 里有 EXT-X-KEY —— 没有就说明加密没生效，必须报错。

        以前这里是"改写 key URL"，但 m3u8 里根本没有 EXT-X-KEY，
        正则是纯 no-op 却照样打"改写成功"日志 —— 排查时极具误导性。
        """
        def _check() -> bool:
            with open(m3u8_path, "r", encoding="utf-8", newline="") as f:
                for line in f:
                    if line.startswith("#EXT-X-KEY"):
                        return True
            return False

        has_key = await asyncio.to_thread(_check)
        if not has_key:
            raise RuntimeError(
                "HLS 加密未生效：m3u8 中缺少 #EXT-X-KEY，拒绝上传明文分片"
            )
        log.info("hls_encryption_verified", path=m3u8_path)

    async def _extract_fingerprints(self, input_path: str, video_id: int, work_dir: Optional[str] = None) -> str:
        """
        提取视频帧指纹
        算法：
        - 每 30s 提取 1 个关键帧
        - 计算 pHash（64-bit）
        - 多帧冗余（同一指纹在视频中出现 3 次以提高鲁棒性）
        参考方案书 4.4.1 节

        整个函数体跑在 asyncio.to_thread 里 —— cv2 解码 + resize +
        phash 全是同步 CPU 密集操作，放在事件循环里会把整个 worker
        卡死（prefetch=10 的其余任务、/health 端点全部无响应）。
        """
        if work_dir is None:
            work_dir = tempfile.mkdtemp(prefix=f"edam-fp-{video_id}-")
        output_path = os.path.join(work_dir, "fingerprint.json")

        def _do_extract() -> None:
            cap = cv2.VideoCapture(input_path)
            try:
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
            finally:
                # 必须放在 finally —— 中途异常会跳过 release，
                # 泄漏文件句柄 + 解码器
                cap.release()

            log.info(
                "fingerprint_extraction_complete",
                video_id=video_id,
                count=len(fingerprints),
            )

            with open(output_path, "w", encoding="utf-8", newline="\n") as f:
                json.dump({
                    "video_id": video_id,
                    "fingerprint_count": len(fingerprints),
                    "fingerprints": fingerprints,
                    "extracted_at": datetime.datetime.utcnow().isoformat() + "Z",
                }, f, ensure_ascii=False, indent=2)

        await asyncio.to_thread(_do_extract)
        return output_path

    async def _upload_hls(self, hls_dir: str, video_id: int) -> None:
        """上传 HLS 切片到 MinIO"""
        for root, _, files in os.walk(hls_dir):
            for fname in files:
                if fname == "key.info":
                    # key.info 是 FFmpeg 的中间产物，不该暴露到对象存储
                    continue
                local_file = os.path.join(root, fname)
                object_name = f"videos/{video_id}/hls/{fname}"
                await asyncio.to_thread(
                    self.minio.fput_object,
                    settings.MINIO_BUCKET_VIDEOS,
                    object_name,
                    local_file,
                    content_type="application/octet-stream",
                )

    async def _upload_key(self, key: bytes, video_id: int) -> None:
        """上传 AES-128 密钥到 MinIO（放在 hls/ 目录之外）。

        独立路径 ``videos/{id}/key.bin`` 而不是 ``videos/{id}/hls/key.bin``：
        hls/ 目录下的东西语义上都是"播放器要拉的"，key 应当只由
        后端 getKey 端点（带 token 校验）吐出。
        """
        def _put() -> None:
            self.minio.put_object(
                settings.MINIO_BUCKET_VIDEOS,
                f"videos/{video_id}/key.bin",
                io.BytesIO(key),
                length=len(key),
                content_type="application/octet-stream",
            )

        await asyncio.to_thread(_put)

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

    def _cleanup_temp(self, path: Optional[str]) -> None:
        """清理工作目录（HLS 分片 + 指纹 JSON + 原始下载文件都在里面）。

        以前只删了"下载文件的父目录"，而 HLS 目录和指纹 JSON 落在
        /tmp 下且从不清除 —— 长驻 worker 每处理一个视频就在容器
        可写层留下完整分片集，几百个视频后磁盘写满。
        """
        if not path:
            return
        try:
            import shutil
            if os.path.isdir(path):
                shutil.rmtree(path, ignore_errors=True)
            elif os.path.exists(path):
                os.unlink(path)
        except Exception as e:
            log.warning("temp_cleanup_failed", path=path, error=str(e))