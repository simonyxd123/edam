"""
DCT 文档水印嵌入服务（v3.3 W-6.2）

替代 POC 阶段：使用 blind-watermark 库生产集成
"""
import io
import os
import logging
import tempfile
from typing import Tuple, Optional, Dict, Any
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class WatermarkEmbedResult:
    """水印嵌入结果"""
    success: bool
    output_path: str
    watermark_text: str
    embedding_algo: str = "DCT-BC"
    embedding_strength: float = 0.10
    duration_ms: int = 0
    error: Optional[str] = None


class DocWatermarkService:
    """
    DCT 频域水印嵌入（图片文档）

    算法：blind-watermark 库（DCT 中频系数嵌入）
    标准：参考 Zauner 2010 + blind-watermark README
    """

    DEFAULT_STRENGTH = 0.10  # 默认嵌入强度（0-1）
    SUPPORTED_FORMATS = {"jpg", "jpeg", "png", "bmp", "tiff"}

    def __init__(self):
        self._bwm = None

    def _get_bwm(self):
        """懒加载 blind-watermark"""
        if self._bwm is None:
            try:
                from blind_watermark import WaterMark
                self._bwm_class = WaterMark
            except ImportError:
                raise RuntimeError(
                    "blind-watermark not installed. Run: pip install blind-watermark==0.4.1")
        return self._bwm_class

    def embed_image_watermark(
        self,
        input_path: str,
        output_path: str,
        watermark_text: str,
        password_wm: int = 1,
        password_img: int = 1
    ) -> WatermarkEmbedResult:
        """嵌入水印到图片

        Args:
            input_path: 原图路径
            output_path: 输出路径
            watermark_text: 水印文本（如 "USER_SA0001_20260827"）
            password_wm: 水印密码（默认 1）
            password_img: 图像密码（默认 1）
        """
        import time
        start = time.time()

        try:
            ext = input_path.rsplit('.', 1)[-1].lower()
            if ext not in self.SUPPORTED_FORMATS:
                return WatermarkEmbedResult(
                    success=False,
                    output_path="",
                    watermark_text=watermark_text,
                    error=f"Unsupported format: {ext}"
                )

            WaterMark = self._get_bwm()
            bwm = WaterMark(password_wm=password_wm, password_img=password_img)
            wm_bytes = watermark_text.encode("utf-8")

            bwm.read_img(input_path)
            bwm.read_wm(wm_bytes, mode="bit")
            bwm.embed(output_path)

            duration_ms = int((time.time() - start) * 1000)
            logger.info(
                "doc_watermark_embedded input=%s output=%s length=%d ms=%d",
                input_path, output_path, len(wm_bytes), duration_ms
            )
            return WatermarkEmbedResult(
                success=True,
                output_path=output_path,
                watermark_text=watermark_text,
                embedding_strength=self.DEFAULT_STRENGTH,
                duration_ms=duration_ms,
            )
        except Exception as e:
            logger.error("doc_watermark_embed_failed error=%s", e)
            return WatermarkEmbedResult(
                success=False,
                output_path="",
                watermark_text=watermark_text,
                error=str(e)
            )

    def extract_image_watermark(
        self,
        image_path: str,
        password_wm: int = 1,
        password_img: int = 1,
        expected_length: int = None
    ) -> Optional[str]:
        """提取水印文本

        Args:
            image_path: 待检测图片路径
            password_wm/p_img: 同嵌入
            expected_length: 期望水印长度（字节）
        """
        try:
            WaterMark = self._get_bwm()
            bwm = WaterMark(password_wm=password_wm, password_img=password_img)
            wm_bits = bwm.extract(image_path, mode="bit", wm_shape=(expected_length * 8,) if expected_length else None)
            bits = [1 if x else 0 for x in wm_bits]

            text_bytes = bytearray()
            for i in range(0, len(bits) - 7, 8):
                byte_bits = bits[i:i+8]
                if len(byte_bits) < 8:
                    break
                text_bytes.append(int("".join(str(b) for b in byte_bits), 2))

            return text_bytes.decode("utf-8", errors="ignore")
        except Exception as e:
            logger.error("doc_watermark_extract_failed error=%s", e)
            return None

    def batch_embed(
        self,
        input_dir: str,
        output_dir: str,
        watermark_text: str
    ) -> Dict[str, WatermarkEmbedResult]:
        """批量嵌入目录中所有图片"""
        os.makedirs(output_dir, exist_ok=True)
        results = {}
        for filename in os.listdir(input_dir):
            ext = filename.rsplit('.', 1)[-1].lower() if '.' in filename else ''
            if ext not in self.SUPPORTED_FORMATS:
                continue
            input_path = os.path.join(input_dir, filename)
            output_path = os.path.join(output_dir, filename)
            results[filename] = self.embed_image_watermark(
                input_path, output_path, watermark_text
            )
        return results


# 单例
doc_watermark_service = DocWatermarkService()