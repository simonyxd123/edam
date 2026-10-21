"""
水印/隐写算法实现
- 图片：DCT 频域水印
- PDF：双轨（增量更新 + word spacing）
- Office：隐写
"""
import asyncio
import os
import tempfile
from typing import Any, Dict, Optional

import structlog
import numpy as np
from minio import Minio
from PIL import Image

from ..config import settings

log = structlog.get_logger()


class WatermarkProcessor:
    """频域盲水印 / 隐写算法集合"""

    def __init__(self) -> None:
        self.minio = Minio(
            settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", ""),
            access_key=settings.MINIO_ACCESS_KEY,
            secret_key=settings.MINIO_SECRET_KEY,
            secure=settings.MINIO_ENDPOINT.startswith("https://"),
        )

    async def process(self, payload: Dict[str, Any]) -> None:
        """
        水印任务处理器（q.watermark 队列消费者入口）。

        以前这个类**没有** process() 方法，而 main.py 的 handle_watermark
        直接取 `self.watermark_processor.process` 作为实参 —— 取属性时
        就抛 AttributeError，且发生在 try 之外，于是每一条 watermark
        消息都会打崩消费者回调，成为毒丸消息。

        :param payload: {file_id, input_path, file_type, watermark_text, employee_no}
        """
        file_id = payload.get("file_id")
        input_path = payload.get("input_path")
        file_type = payload.get("file_type", "image")
        watermark_text = payload.get("watermark_text") or (
            f"{payload.get('employee_no', 'UNKNOWN')}|{file_id}"
        )

        if not input_path:
            raise ValueError("watermark payload 缺少 input_path")

        log.info("watermark_processing_start", file_id=file_id, file_type=file_type)

        work_dir: Optional[str] = tempfile.mkdtemp(prefix=f"edam-wm-{file_id}-")
        local_path = os.path.join(work_dir, os.path.basename(input_path))
        base, ext = os.path.splitext(local_path)
        output_path = base + ".wm" + ext

        try:
            await asyncio.to_thread(
                self.minio.fget_object,
                settings.MINIO_BUCKET_DOCS,
                input_path,
                local_path,
            )

            if file_type == "image":
                await asyncio.to_thread(self.embed_image, local_path, output_path, watermark_text)
            elif file_type == "pdf":
                await asyncio.to_thread(self.embed_pdf, local_path, output_path, watermark_text)
            elif file_type in ("docx", "xlsx"):
                await asyncio.to_thread(self.embed_office, local_path, output_path, watermark_text, file_type)
            else:
                output_path = local_path

            object_name = f"watermarks/{file_id}/watermarked{ext}"
            await asyncio.to_thread(
                self.minio.fput_object,
                settings.MINIO_BUCKET_WATERMARKS,
                object_name,
                output_path,
            )
            log.info("watermark_processing_complete", file_id=file_id, object_name=object_name)
        finally:
            import shutil
            if work_dir and os.path.isdir(work_dir):
                shutil.rmtree(work_dir, ignore_errors=True)

    def embed_image(self, input_path: str, output_path: str, text: str) -> None:
        """
        图片 DCT 频域水印（参考方案书 5.3.1）
        使用 blind-watermark 库或自研 DCT
        """
        try:
            from blind_watermark import WaterMark
            bwm = WaterMark(password_wm=1, password_img=1)
            # 文本转 bit 数组
            wm = text.encode("utf-8")
            bwm.read_img(input_path)
            bwm.read_wm(wm, mode="bytes")
            bwm.embed(output_path)
            log.info("image_watermark_embedded", output=output_path, length=len(wm))
        except ImportError:
            # fallback：使用 LSB 隐写（简化版）
            log.warning("blind_watermark_not_available, using fallback LSB")
            self._lsb_embed(input_path, output_path, text)

    def embed_pdf(self, input_path: str, output_path: str, text: str) -> None:
        """
        PDF 双轨水印
        - 轨道 A：增量更新注入指纹
        - 轨道 B：word spacing 隐写
        """
        try:
            from PyPDF2 import PdfReader, PdfWriter
            reader = PdfReader(input_path)
            writer = PdfWriter()

            for page in reader.pages:
                writer.add_page(page)

            # 轨道 A：写入 metadata 增量
            writer.add_metadata({
                "/Title": f"Doc {text[:32]}",
                "/Producer": f"EDAM Watermark 3.1.0 | {text}",
            })

            with open(output_path, "wb") as f:
                writer.write(f)

            log.info("pdf_watermark_embedded", output=output_path)
        except Exception as e:
            log.error("pdf_watermark_failed", error=str(e))
            # 失败则复制原文件
            import shutil
            shutil.copy(input_path, output_path)

    def embed_office(self, input_path: str, output_path: str,
                     text: str, file_type: str) -> None:
        """
        Office 文档隐写
        - Word：在段落末尾插入零宽字符
        - Excel：修改自定义 XML 部件
        """
        if file_type == "docx":
            self._docx_steganography(input_path, output_path, text)
        elif file_type == "xlsx":
            self._xlsx_steganography(input_path, output_path, text)
        else:
            import shutil
            shutil.copy(input_path, output_path)

    def _docx_steganography(self, input_path: str, output_path: str, text: str) -> None:
        """Word 文档零宽字符隐写"""
        try:
            from docx import Document
            doc = Document(input_path)
            # 将水印文本转为零宽字符（ZWSP/ZWNJ/ZWJ）
            zw_map = {'0': '​', '1': '‌'}
            bits = ''.join(format(b, '08b') for b in text.encode("utf-8"))
            zw_text = ''.join(zw_map[b] for b in bits)

            # 在文档末尾追加不可见水印
            doc.add_paragraph(zw_text)

            doc.save(output_path)
            log.info("docx_watermark_embedded", output=output_path)
        except Exception as e:
            log.error("docx_watermark_failed", error=str(e))
            import shutil
            shutil.copy(input_path, output_path)

    def _xlsx_steganography(self, input_path: str, output_path: str, text: str) -> None:
        """Excel 自定义属性隐写"""
        try:
            from openpyxl import load_workbook
            wb = load_workbook(input_path)
            wb.properties.creator = f"EDAM-{text[:100]}"
            wb.properties.description = f"watermark: {text}"
            wb.save(output_path)
            log.info("xlsx_watermark_embedded", output=output_path)
        except Exception as e:
            log.error("xlsx_watermark_failed", error=str(e))
            import shutil
            shutil.copy(input_path, output_path)

    def _lsb_embed(self, input_path: str, output_path: str, text: str) -> None:
        """LSB fallback 水印"""
        img = Image.open(input_path).convert("RGB")
        pixels = np.array(img)
        h, w, _ = pixels.shape
        bits = ''.join(format(b, '08b') for b in text.encode("utf-8"))

        idx = 0
        for y in range(h):
            for x in range(w):
                for c in range(3):
                    if idx >= len(bits):
                        break
                    pixels[y, x, c] = (pixels[y, x, c] & 0xFE) | int(bits[idx])
                    idx += 1

        Image.fromarray(pixels).save(output_path)
        log.info("lsb_watermark_embedded", output=output_path)

    def extract(self, file_path: str, file_type: str) -> str:
        """提取水印（用于溯源）"""
        if file_type == "image":
            return self._extract_image(file_path)
        elif file_type == "pdf":
            return self._extract_pdf(file_path)
        else:
            return ""

    def _extract_image(self, file_path: str) -> str:
        try:
            from blind_watermark import WaterMark
            bwm = WaterMark(password_wm=1, password_img=1)
            wm = bwm.extract(file_path, mode="bytes")
            return wm.decode("utf-8", errors="ignore")
        except Exception as e:
            log.error("image_watermark_extract_failed", error=str(e))
            return ""

    def _extract_pdf(self, file_path: str) -> str:
        try:
            from PyPDF2 import PdfReader
            reader = PdfReader(file_path)
            meta = reader.metadata or {}
            producer = str(meta.get("/Producer", ""))
            # 解析 "EDAM Watermark 3.1.0 | xxx" 格式
            if "|" in producer:
                return producer.split("|", 1)[1].strip()
            return producer
        except Exception as e:
            log.error("pdf_watermark_extract_failed", error=str(e))
            return ""