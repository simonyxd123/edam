-- ============================================================================
-- V20261106_1000__doc_watermark_path.sql
--
-- doc_resource 补一列：嵌入水印后的文档在 MinIO 里的对象名。
--
-- 为什么需要：
--   Worker 处理文档时产出**两个**对象 ——
--     documents/{doc_id}/watermarked{ext}   （嵌了水印的原文）
--     documents/{doc_id}/preview{ext}       （预览件）
--   preview_path 早就有了（V20260812_1000），但水印产物的位置**无处可记**：
--   doc_resource 没有对应列，doc_watermark.minio_path 又没有任何 Java 代码写入。
--   结果就是水印做完之后，系统里查不到"加过水印的原文在哪"。
--
-- 迁移只增不改 —— 不动已应用的迁移，加新列走新文件。
-- ============================================================================

ALTER TABLE doc_resource
    ADD COLUMN watermark_path VARCHAR(512) NULL
        COMMENT '嵌入水印后的文档在 MinIO 中的对象名' AFTER preview_path;
