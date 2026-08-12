"""
文档处理器
- 频域盲水印 / 隐写术
- 格式转换（如 Word → PDF 预览）
"""
import asyncio
import io
import os
import tempfile
from typing import Any, Dict

import structlog
from minio import Minio

from ..config import settings
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

        try:
            # 1. 下载
            local_path = await self._download(settings.MINIO_BUCKET_DOCS, input_path)

            # 2. 频域盲水印 / 隐写（如启用）
            if enable_watermark:
                watermarked_path = await self._watermark(
                    local_path, doc_id, file_type, payload
                )
            else:
                watermarked_path = local_path

            # 3. 格式转换（如 Word → PDF 预览）
            preview_path = await self._convert_to_preview(watermarked_path, doc_id, file_type)

            # 4. 上传
            await self._upload(watermarked_path, doc_id, "watermarked")
            await self._upload(preview_path, doc_id, "preview")

            log.info("document_processing_complete", doc_id=doc_id)

        except Exception as e:
            log.error("document_processing_failed", doc_id=doc_id, error=str(e))
            raise
        finally:
            self._cleanup(local_path)

    async def _download(self, bucket: str, object_name: str) -> str:
        tmp = tempfile.mktemp(suffix=os.path.splitext(object_name)[1])
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

        output_path = local_path + ".wm"

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
        if file_type == "docx":
            # 使用 LibreOffice 转 PDF
            cmd = [
                "libreoffice", "--headless", "--convert-to", "pdf",
                "--outdir", os.path.dirname(local_path),
                local_path,
            ]
            proc = await asyncio.create_subprocess_exec(
                *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            )
            await proc.communicate()
            return local_path.replace(".docx", ".pdf")
        return local_path

    async def _upload(self, local_path: str, doc_id: int, kind: str) -> None:
        object_name = f"documents/{doc_id}/{kind}{os.path.splitext(local_path)[1]}"
        await asyncio.to_thread(
            self.minio.fput_object,
            settings.MINIO_BUCKET_DOCS, object_name, local_path,
        )

    def _cleanup(self, path: str) -> None:
        try:
            os.unlink(path)
        except Exception:
            pass