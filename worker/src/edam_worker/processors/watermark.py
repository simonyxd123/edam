"""
水印/隐写算法实现
- 图片：DCT 频域水印
- PDF：双轨（增量更新 + word spacing）
- Office：隐写
"""
import os

import structlog
import numpy as np
from PIL import Image

from ..config import settings
from ..errors import PermanentWorkerError

log = structlog.get_logger()


class WatermarkProcessor:
    """频域盲水印 / 隐写算法集合。

    <p>纯算法类：只处理**本地路径**，不碰 MinIO —— 上传下载由调用方
    （{@code processors/document.py} 的 {@code DocumentProcessor}）负责。
    """

    # 注：v3.4.1 删掉了原先的 `process()` 与 MinIO 客户端 —— 它是 q.watermark
    # 队列的唯一入口，而那条队列**从来没有生产者**（后端从不往 "watermark"
    # 路由发消息）。真实的盲水印嵌入走的是 q.document.preprocess
    # （processors/document.py 的 DocumentProcessor._watermark → 下面的 embed_*），
    # 产物落在 documents/{doc_id}/watermarked*，并由文档状态回调回写路径。
    #
    # 保留一条"已声明、已绑定、已被消费、已被监控、但永远是空"的队列，
    # 会让运维从监控上误以为这条链路是活的 —— 所以整条支线一起删掉。

    def embed_image(self, input_path: str, output_path: str, text: str) -> None:
        """
        图片 DCT 频域水印（参考方案书 5.3.1）
        使用 blind-watermark 库或自研 DCT
        """
        try:
            from blind_watermark import WaterMark
            # 密码来自部署配置（2026-11-14 第六轮）—— 此前硬编码 1，
            # 等于把提取/伪造/抹除的门槛设成零。
            bwm = WaterMark(password_wm=settings.EDAM_WATERMARK_PASSWORD,
                            password_img=settings.EDAM_WATERMARK_PASSWORD)
            # 文本转 bit 数组
            wm = text.encode("utf-8")
            bwm.read_img(input_path)
            bwm.read_wm(wm, mode="bytes")
            bwm.embed(output_path)
            log.info("image_watermark_embedded", output=output_path, length=len(wm))
        except ImportError:
            # fallback：使用 LSB 隐写（简化版）
            # ⚠️ LSB 极脆弱：裁掉顶部 1~2 行就能抹掉 —— 它只挡"完全不懂行"的人。
            # 但没有它，blind-watermark 没装时图片就完全不带指纹了。
            log.warning("blind_watermark_not_available, using fallback LSB")
            self._lsb_embed(input_path, output_path, text)

    def embed_pdf(self, input_path: str, output_path: str, text: str) -> None:
        """
        PDF 指纹写入。

        ⚠️ **这不是盲水印**（2026-11-14 代码审计第六轮 §2）：
        指纹被**明文**写进 PDF 的文档属性（{@code /Title}、{@code /Producer}）。
        收件人打开"文件 → 属性"就能直接看到
        {@code 上传者ID|文档ID|时间戳} ⇒
        - 溯源信息**主动泄露**给了每一个拿到文件的人；
        - 指纹可以被**随手改写**（伪造）或**直接删掉**（抹除）。

        真正要堵住这两点，需要的是"登记值比对"（后端已实现，见
        {@code WatermarkRegistryService}）与"非明文信道"，本函数解决不了后者。
        这里至少保证：**写不进去就报错，绝不产出"看起来处理好了、其实没指纹"的原件**。

        <p>失败处理（2026-11-14 第六轮改动）：
        此前是 {@code except: shutil.copy(原文件)} ——
        嵌入失败时**原文照常流转、完全没有指纹**，
        而流水线仍把 {@code watermark_status} 报成 {@code 2 (ready)}
        ⇒ 系统认定"已加水印"，溯源链**静默断裂**，监控上什么都看不到。
        现在失败一律抛 {@link PermanentWorkerError}：
        文档处理失败、状态报 3 (failed)、任务记 failed_permanent。
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
            raise PermanentWorkerError(
                f"PDF 指纹写入失败，文档不予放行（避免无指纹原件流出去）: {e}"
            ) from e

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
            # 失败则复制原文件 → 文档照常流转、却完全没有指纹（第六轮修掉）
            raise PermanentWorkerError(
                f"Word 隐写失败，文档不予放行: {e}"
            ) from e

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
            # 失败则复制原文件 → 文档照常流转、却完全没有指纹（第六轮修掉）
            raise PermanentWorkerError(
                f"Excel 隐写失败，文档不予放行: {e}"
            ) from e

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
        """
        提取图片盲水印。

        <p>依次尝试"配置里的密码"与"历史硬编码的 1"——
        后者是**兼容**：本功能上线前嵌入的水印用的就是 1，
        直接换密码会让那些文件从此提取不出来。
        """
        try:
            from blind_watermark import WaterMark
            candidates = []
            configured = settings.EDAM_WATERMARK_PASSWORD
            if configured != 1:
                candidates.append(configured)
            candidates.append(1)
            last_error = None
            for password in candidates:
                try:
                    bwm = WaterMark(password_wm=password, password_img=password)
                    wm = bwm.extract(file_path, mode="bytes")
                    if wm:
                        return wm.decode("utf-8", errors="ignore")
                except Exception as e:  # 密码不对 / 文件不是带水印的图片
                    last_error = e
                    continue
            log.error("image_watermark_extract_failed", error=str(last_error))
            return ""
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