package com.example.edam.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 文档防泄密水印服务（v3.2 文档防泄密）
 *
 * 支持：
 * - PDF：在每页对角线方向叠加半透明水印（员工号 + 时间戳）
 * - 图片：右下角叠加半透明水印
 *
 * 注意：此水印为"事后审计"手段（事后追责），不是"事前防截屏"。
 * 真正的防截屏需要 DRM / 视频流加密 / 屏幕录像防护等客户端层防护。
 */
@Slf4j
@Service
public class WatermarkService {

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 给 PDF 字节流加水印，返回新的字节流
     * 失败时返回原字节流（降级）
     */
    public byte[] watermarkPdf(InputStream pdfStream, String employeeNo) throws IOException {
        byte[] original = pdfStream.readAllBytes();
        String text = buildWatermarkText(employeeNo);

        try (PDDocument doc = PDDocument.load(new java.io.ByteArrayInputStream(original))) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getMediaBox();
                float w = box.getWidth();
                float h = box.getHeight();

                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    // 半透明（0.08 alpha = 8%）
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(0.08f);
                    cs.setGraphicsStateParameters(gs);

                    cs.setFont(font, 36);
                    cs.setNonStrokingColor(180, 180, 180);  // 浅灰

                    // 对角线 45° 平铺
                    cs.beginText();
                    for (float y = 0; y < h + w; y += 100) {
                        // PDF 坐标系：左下角原点，y 向上
                        cs.setTextMatrix(
                            new org.apache.pdfbox.util.Matrix(
                                (float) Math.cos(Math.toRadians(45)),
                                (float) Math.sin(Math.toRadians(45)),
                                -(float) Math.sin(Math.toRadians(45)),
                                (float) Math.cos(Math.toRadians(45)),
                                -50,
                                y
                            )
                        );
                        cs.showText(text);
                    }
                    cs.endText();
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            log.info("pdf_watermark_applied, employee_no={}, pages={}, in_size={}, out_size={}",
                employeeNo, doc.getNumberOfPages(), original.length, out.size());
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("pdf_watermark_failed, fall_back_to_original, error={}", e.toString());
            return original;
        }
    }

    /**
     * 给图片字节流加水印
     * 失败时返回原字节流
     */
    public byte[] watermarkImage(InputStream imgStream, String mimeType, String employeeNo) throws IOException {
        byte[] original = imgStream.readAllBytes();
        String text = buildWatermarkText(employeeNo);

        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) return original;

            int w = src.getWidth();
            int h = src.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(src, 0, 0, null);

            // 右下角斜向水印
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g.setColor(new Color(128, 128, 128));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, w / 50)));
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getHeight();
            // 旋转 -30°
            g.rotate(-Math.PI / 6, w - textW - 20, h - textH - 20);
            g.drawString(text, w - textW - 20, h - 20);
            g.dispose();

            String outFmt = mimeType != null && mimeType.contains("png") ? "png" : "jpg";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, outFmt, baos);
            log.info("image_watermark_applied, employee_no={}, format={}", employeeNo, outFmt);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("image_watermark_failed, fall_back_to_original, error={}", e.toString());
            return original;
        }
    }

    /**
     * 通用入口：根据 MIME 决定是否加水印
     */
    public byte[] applyWatermark(InputStream in, String mimeType, String employeeNo) throws IOException {
        if (mimeType == null || employeeNo == null) {
            return in.readAllBytes();
        }
        String m = mimeType.toLowerCase();
        if (m.equals("application/pdf") || m.contains("pdf")) {
            return watermarkPdf(in, employeeNo);
        }
        if (m.startsWith("image/png") || m.startsWith("image/jpeg") ||
            m.startsWith("image/jpg") || m.startsWith("image/webp")) {
            return watermarkImage(in, mimeType, employeeNo);
        }
        // Office 等：不在预览流上加水印，依赖前端 / 客户端 DRM
        return in.readAllBytes();
    }

    private String buildWatermarkText(String employeeNo) {
        return "EDAM " + employeeNo + " " + TS_FMT.format(OffsetDateTime.now());
    }
}