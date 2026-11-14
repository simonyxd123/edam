"""
DCT 文档水印嵌入服务（v3.3 W-6.2）

替代 POC 阶段：使用 blind-watermark 库生产集成

**v3.4.1：已统一到 `processors/watermark.py` 的 `WatermarkProcessor`。**

此前本模块用 blind-watermark 的 `mode="bit"` 嵌入 + `mode="bit"` 提取，
而 `processors/watermark.py` 用的是 `mode="bytes"` ——
**两套编码互相读不出来**：bit 模式嵌进去的水印，bytes 模式提不出来；
反过来也一样。而真实链路（`processors/document.py`）走的是后者，
于是本模块就算被接上，产出的水印也**提不出来**。

现在本模块只是 `WatermarkProcessor` 的一层薄封装（带结果对象 + 批量入口），
嵌入与提取走同一套实现，**能互相读**。

接线状态：算法已统一且可用；产品侧的溯源闭环
（`POST /watermarks/extract` → Worker → 指纹库比对）见 README 的「已知限制」。
"""
import os
import logging
import time
from typing import Optional, Dict, List
from dataclasses import dataclass

from ..processors.watermark import WatermarkProcessor

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

    委托给 `processors/watermark.py` 的 `WatermarkProcessor` ——
    全仓库唯一的水印实现（图片走 blind-watermark 的 DCT，缺库时降级 LSB）。

    标准：参考 Zauner 2010 + blind-watermark README
    """

    DEFAULT_STRENGTH = 0.10  # 记录用；blind-watermark 的 embed() 不接受强度参数
    SUPPORTED_FORMATS = {"jpg", "jpeg", "png", "bmp", "tiff"}

    def __init__(self):
        self._processor = WatermarkProcessor()

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
            password_wm: 水印密码 —— **本参数已不再生效**（只为兼容旧调用）。
                真实密码来自部署配置 {@code EDAM_WATERMARK_PASSWORD}
                （2026-11-14 代码审计第六轮改：此前硬编码 1，等于把
                提取/伪造/抹除的门槛设成零）
            password_img: 图像密码 —— 同上，已不再生效
        """
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

            # 走权威实现 —— 与真实上传链路（processors/document.py）完全一致
            self._processor.embed_image(input_path, output_path, watermark_text)

            duration_ms = int((time.time() - start) * 1000)
            logger.info(
                "doc_watermark_embedded input=%s output=%s length=%d ms=%d",
                input_path, output_path, len(watermark_text), duration_ms
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

        与嵌入走同一套实现（`mode="bytes"`）—— 此前本方法用 `mode="bit"`
        提取，而真实链路用 `mode="bytes"` 嵌入，**提出来永远是空的**。

        Args:
            image_path: 待检测图片路径
            password_wm/p_img: 保留兼容；真实密码来自部署配置 EDAM_WATERMARK_PASSWORD
            expected_length: 已废弃 —— bytes 模式长度自描述，不需要它
        """
        try:
            text = self._processor.extract(image_path, "image")
            if not text:
                logger.info("doc_watermark_extract_empty path=%s", image_path)
                return None
            return text
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
        results: Dict[str, WatermarkEmbedResult] = {}
        for filename in sorted(os.listdir(input_dir)):
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
