-- ==========================================================
-- V2: 扩 mime_type 字段长度
-- ==========================================================
-- 原因：V1 用 VARCHAR(64)，但 Excel 的 MIME
--   application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
-- 有 66 字符，Data truncation 报错
--
-- 修复：所有相关表的 mime_type 改 VARCHAR(255)

ALTER TABLE doc_resource
  MODIFY COLUMN mime_type VARCHAR(255) NULL;

ALTER TABLE video_resource
  MODIFY COLUMN mime_type VARCHAR(64) NULL;
-- 视频 MIME 较短（video/mp4 等），保持 64；如需可单独扩