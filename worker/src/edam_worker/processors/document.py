"""
文档处理器
- 频域盲水印 / 隐写术
- 格式转换（如 Word → PDF 预览）
"""
import asyncio
import os
import tempfile
from typing import Any, Dict, Optional

import requests
import structlog
from minio import Minio

from ..config import backend_api_url, settings
from ..procutil import kill_process
from ..errors import PermanentWorkerError
from .watermark import WatermarkProcessor

log = structlog.get_logger()


class DocumentProcessor:
    """文档异步处理"""

    def __init__(self) -> None:
        self.minio = Minio(
            settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", ""),
            access_key=settings.MINIO_ACCESS_KEY,
            secret_key=settings.MINIO_SECRET_KEY,
            secure=settings.MINIO_ENDPOINT.startswith("https://"),
        )
        self.watermark_processor = WatermarkProcessor()

    async def process(self, payload: Dict[str, Any]) -> None:
        """
        文档处理流水线
        :param payload: {doc_id, input_path, file_type, classification_lv, uploader_id, enable_watermark}
        """
        doc_id = payload["doc_id"]
        input_path = payload["input_path"]
        file_type = payload.get("file_type", "docx")
        enable_watermark = payload.get("enable_watermark", True)

        log.info("document_processing_start", doc_id=doc_id, file_type=file_type)

        # 初始化为 None —— 下面 finally 会引用；
        # 下载阶段就抛异常时变量未定义，finally 里的 NameError
        # 会把真正的原始异常顶替掉
        local_path: Optional[str] = None
        work_dir: Optional[str] = None

        # 水印 / 预览两个状态分开报 —— "水印成功但预览失败"是常见情况，
        # 一刀切报一个状态会把已经做完的那半也标成失败
        # 状态码与 ResourceEnums.processStatus 对齐：
        #   0=pending  1=processing  2=ready  3=failed  4=skipped
        watermark_status = 0
        preview_status = 0
        watermark_path: Optional[str] = None
        preview_path: Optional[str] = None
        failed = False

        try:
            # 1. 下载到工作目录
            work_dir = tempfile.mkdtemp(prefix=f"edam-doc-{doc_id}-")
            local_path = await self._download(
                settings.MINIO_BUCKET_DOCS, input_path, work_dir
            )

            # 2. 频域盲水印 / 隐写（如启用）
            if enable_watermark:
                watermarked_path = await self._watermark(
                    local_path, doc_id, file_type, payload
                )
            else:
                watermarked_path = local_path

            # 3. 格式转换（如 Word → PDF 预览）
            converted = await self._convert_to_preview(watermarked_path, doc_id, file_type)

            # 4. 上传
            #    状态与路径**必须在上传成功之后**才置 ready ——
            #    否则会把一个没传上去的产物报成"已就绪"。
            await self._upload(watermarked_path, doc_id, "watermarked", file_type)
            if enable_watermark:
                watermark_path = f"documents/{doc_id}/watermarked{file_type}"
                watermark_status = 2      # ready
            else:
                watermark_status = 4      # skipped

            await self._upload(converted, doc_id, "preview", self._preview_ext(file_type))
            preview_path = f"documents/{doc_id}/preview{self._preview_ext(file_type)}"
            preview_status = 2          # ready

            log.info("document_processing_complete", doc_id=doc_id)

        except Exception as e:
            failed = True
            # 失败时把还停在 pending 的那半标成 failed（3=failed）
            if watermark_status == 0:
                watermark_status = 3
            if preview_status == 0:
                preview_status = 3
            log.error("document_processing_failed", doc_id=doc_id, error=str(e))
            raise
        finally:
            # 回调后端 —— **成功和失败都要报**。
            # 否则 watermark_status / preview_status 会永远停在 pending，
            # 前端的状态标签恒为 pending，用户永远等不到结果。
            try:
                await self._notify_backend_status(
                    doc_id, watermark_status, preview_status,
                    watermark_path, preview_path,
                )
            except Exception as cb_error:
                # 回调失败不能顶替掉原始异常 —— 但必须让任务被记成失败，
                # 否则后端永远等不到状态
                log.error("document_status_callback_failed", doc_id=doc_id,
                          error=str(cb_error))
                if not failed:
                    raise
            # 清理整个工作目录 —— 原来只删了 local_path，
            # .wm / .pdf 中间产物全部泄漏在磁盘上
            self._cleanup(work_dir)

    async def _notify_backend_status(
        self,
        doc_id: int,
        watermark_status: int,
        preview_status: int,
        watermark_path: Optional[str],
        preview_path: Optional[str],
    ) -> None:
        """回调后端更新文档处理状态。

        与视频侧（processors/video.py）同一套约定：
        - 带 X-Worker-Token（后端 fail-closed，不配就一律 401）
        - 401/403 → PermanentWorkerError（配置问题，重试无意义）
        - 其它 4xx → PermanentWorkerError（请求本身有问题）
        - 5xx / 网络异常 → 指数退避重试，耗尽后抛普通异常让外层重跑任务
        """
        url = backend_api_url(f"documents/{doc_id}/status")
        payload: Dict[str, Any] = {
            "watermark_status": watermark_status,
            "preview_status": preview_status,
        }
        if watermark_path:
            payload["watermark_path"] = watermark_path
        if preview_path:
            payload["preview_path"] = preview_path

        headers = {}
        if settings.WORKER_TOKEN:
            headers["X-Worker-Token"] = settings.WORKER_TOKEN
        else:
            log.warning(
                "worker_token_not_configured",
                doc_id=doc_id,
                hint=("后端要求 X-Worker-Token（fail-closed）。不配的话回调一律 401，"
                      "文档处理完成了 watermark_status 也不会变 —— 前端永远显示 pending"),
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
                last_error = f"{type(e).__name__}: {e}"
                log.warning("notify_backend_exception", doc_id=doc_id,
                            attempt=attempt, max_attempts=max_attempts, error=last_error)
            else:
                if resp.status_code < 400:
                    log.info("notify_backend_ok", doc_id=doc_id, status=resp.status_code)
                    return
                if resp.status_code in (401, 403):
                    raise PermanentWorkerError(
                        f"后端拒绝了回调（HTTP {resp.status_code}）：worker 的 WORKER_TOKEN "
                        f"与后端的 edam.worker.token 不一致，或有一边没配。"
                        f"（后端日志会写明具体是哪种）")
                if resp.status_code < 500:
                    raise PermanentWorkerError(
                        f"后端拒绝了回调（HTTP {resp.status_code}）：{resp.text[:200]}")
                last_error = f"HTTP {resp.status_code}: {resp.text[:200]}"
                log.warning("notify_backend_failed", doc_id=doc_id,
                            attempt=attempt, max_attempts=max_attempts,
                            status=resp.status_code, body=resp.text[:300])

            if attempt < max_attempts:
                await asyncio.sleep(backoff * (2 ** (attempt - 1)))

        raise RuntimeError(f"回调后端失败（已重试 {max_attempts} 次）：{last_error}")

    async def _download(self, bucket: str, object_name: str, work_dir: str) -> str:
        # 不用 tempfile.mktemp —— 它返回"尚不存在"的路径，
        # 随后 fget_object 才创建，存在 TOCTOU 窗口
        tmp = os.path.join(work_dir, os.path.basename(object_name))
        await asyncio.to_thread(self.minio.fget_object, bucket, object_name, tmp)
        return tmp

    async def _watermark(self, local_path: str, doc_id: int,
                        file_type: str, payload: Dict[str, Any]) -> str:
        """
        频域盲水印 / 隐写
        参考方案书 5.3 节：
        - 图片：DCT 频域水印（blind-watermark）
        - PDF：双轨（增量更新指纹 + word spacing 隐写）
        - Word/Excel：隐写（空白字符 / 字体间距）
        """
        uploader_id = str(payload.get("uploader_id", ""))
        watermark_text = f"{uploader_id}|{doc_id}|2026-08-12T14:00:00Z"

        # 用 splitext 换扩展名，不要用字符串拼接 —— 拼接会得到
        # "xxx.docx.wm"，LibreOffice 认不出这个扩展名，转换必然失败
        base, ext = os.path.splitext(local_path)
        output_path = base + ".wm" + ext

        if file_type == "image":
            # DCT 频域水印
            await asyncio.to_thread(
                self.watermark_processor.embed_image,
                local_path, output_path, watermark_text,
            )
        elif file_type == "pdf":
            # 双轨
            await asyncio.to_thread(
                self.watermark_processor.embed_pdf,
                local_path, output_path, watermark_text,
            )
        elif file_type in ("docx", "xlsx"):
            # 隐写
            await asyncio.to_thread(
                self.watermark_processor.embed_office,
                local_path, output_path, watermark_text, file_type,
            )
        else:
            # 其他类型：跳过水印
            output_path = local_path

        return output_path

    async def _convert_to_preview(self, local_path: str, doc_id: int, file_type: str) -> str:
        """
        转换为可在线预览格式
        - docx → PDF
        - xlsx → PDF
        - pdf → 保持
        - image → 缩略图
        """
        if file_type not in ("docx", "xlsx"):
            return local_path

        base, ext = os.path.splitext(local_path)
        cmd = [
            "libreoffice", "--headless", "--convert-to", "pdf",
            # 隔离 UserInstallation —— 不加的话多个 LibreOffice 实例
            # 共享 ~/.config/libreoffice 的单例锁，并发时互相失败
            f"-env:UserInstallation=file://{os.path.dirname(local_path)}/lo-profile",
            "--outdir", os.path.dirname(local_path),
            local_path,
        ]
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        # **必须带超时**（2026-11-08 补）：LibreOffice headless 挂在畸形
        # docx 上是家常便饭，裸 communicate() 会让协程永远不返回，
        # 10 个这样的任务就把 worker 堵死（且没有异常、没有日志）。
        try:
            _, stderr = await asyncio.wait_for(
                proc.communicate(), timeout=settings.LIBREOFFICE_TIMEOUT_SEC)
        except asyncio.TimeoutError:
            await kill_process(proc)
            raise RuntimeError(
                f"LibreOffice 转换超时（{settings.LIBREOFFICE_TIMEOUT_SEC}s）")
        # 必须检查 returncode —— 原来从不检查，LibreOffice 失败也静默通过，
        # 最后把一个不存在的 .pdf 当成"预览成功"上传
        if proc.returncode != 0:
            raise RuntimeError(
                f"LibreOffice 转换失败 (rc={proc.returncode}): "
                f"{stderr.decode('utf-8', errors='replace')[:300]}"
            )
        converted = base + ".pdf"
        if not os.path.exists(converted):
            raise RuntimeError(f"LibreOffice 未产出预期文件: {converted}")
        return converted

    @staticmethod
    def _preview_ext(file_type: str) -> str:
        """预览文件的扩展名（docx/xlsx → pdf，其余保持原类型）"""
        return ".pdf" if file_type in ("docx", "xlsx") else ""

    async def _upload(self, local_path: str, doc_id: int, kind: str, ext: str) -> None:
        # 显式传扩展名，不要用 splitext 猜 —— local_path 可能是
        # "xxx.docx.wm"，splitext 会给出 ".wm"，对象名变成
        # "documents/{id}/watermarked.wm"，Office 完全认不出
        object_name = f"documents/{doc_id}/{kind}{ext}"
        await asyncio.to_thread(
            self.minio.fput_object,
            settings.MINIO_BUCKET_DOCS, object_name, local_path,
        )

    def _cleanup(self, work_dir: Optional[str]) -> None:
        """清理整个工作目录（原始文件 + .wm + .pdf + lo-profile）"""
        if not work_dir:
            return
        try:
            import shutil
            if os.path.isdir(work_dir):
                shutil.rmtree(work_dir, ignore_errors=True)
        except Exception as e:
            log.warning("doc_cleanup_failed", path=work_dir, error=str(e))