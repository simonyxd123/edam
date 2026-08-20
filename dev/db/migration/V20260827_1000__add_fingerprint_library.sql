-- ============================================================================
-- V20260827_1000__add_fingerprint_library.sql
-- v3.3 W-6：频域水印指纹库 + 泄露检测
-- =========================================================================：SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. video_fingerprint：pHash 帧指纹库
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_fingerprint (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    session_id CHAR(64) NOT NULL COMMENT '观看会话 ID',
    frame_index INT UNSIGNED NOT NULL COMMENT '帧索引',
    timestamp_sec DECIMAL(10, 3) NOT NULL DEFAULT 0 COMMENT '时间戳（秒）',
    phash BINARY(8) NOT NULL COMMENT '64 bit pHash',
    computed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_video_user (video_id, user_id),
    KEY idx_user (user_id, computed_at),
    KEY idx_session (session_id),
    KEY idx_phash (phash),
    CONSTRAINT fk_fingerprint_video FOREIGN KEY (video_id) REFERENCES video_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_fingerprint_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='pHash 帧指纹库（v3.3 W-6.1）';

-- 数据生命周期：
-- 保留 365 天（合规要求），过期清理（凌晨 cron）
-- 每个观看会话保留 30 帧指纹

-- ----------------------------------------------------------------------------
-- 2. doc_watermark：DCT 文档水印
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS doc_watermark (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    doc_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    session_id CHAR(64) NOT NULL,
    watermark_text VARCHAR(255) NOT NULL COMMENT '水印文本（工号 + 时间戳）',
    embedding_algo VARCHAR(32) NOT NULL DEFAULT 'DCT-BC' COMMENT 'DCT-BC = blind-watermark',
    embedding_strength DECIMAL(3, 2) NOT NULL DEFAULT 0.10 COMMENT '嵌入强度（0-1）',
    dct_coefficients JSON NULL COMMENT 'DCT 系数位置（用于提取）',
    minio_path VARCHAR(512) NOT NULL COMMENT 'MinIO 中嵌入水印的文档路径',
    embedded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_doc_user (doc_id, user_id),
    KEY idx_user (user_id, embedded_at),
    CONSTRAINT fk_watermark_doc FOREIGN KEY (doc_id) REFERENCES doc_resource (id) ON DELETE CASCADE,
    CONSTRAINT fk_watermark_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='DCT 文档水印（v3.3 W-6.2）';

-- ----------------------------------------------------------------------------
-- 3. leak_detection：泄露检测记录
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS leak_detection (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    detection_id CHAR(64) NOT NULL COMMENT '检测任务 ID',
    resource_type VARCHAR(16) NOT NULL COMMENT 'video / document',
    resource_id BIGINT UNSIGNED NULL,
    leaked_file_path VARCHAR(1024) NOT NULL COMMENT '疑似泄露文件（OSS/对象存储）',
    leaked_file_hash CHAR(64) NULL COMMENT '泄露文件 SHA-256',
    detection_method VARCHAR(32) NOT NULL COMMENT 'phash / dct / manual',
    matched_user_id BIGINT UNSIGNED NULL COMMENT '匹配到的观看者',
    matched_session_id CHAR(64) NULL COMMENT '匹配的会话 ID',
    match_score DECIMAL(5, 2) NOT NULL COMMENT '匹配置信度（0-100）',
    matched_frames INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '匹配帧数（多帧投票）',
    total_frames INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '总查询帧数',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending / confirmed / dismissed',
    detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at DATETIME(3) NULL,
    reviewed_by BIGINT UNSIGNED NULL,
    review_note TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_resource (resource_type, resource_id),
    KEY idx_user (matched_user_id),
    KEY idx_status (status, detected_at),
    KEY idx_detection_id (detection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='泄露检测记录（v3.3 W-6.3）';

-- 数据生命周期：保留 730 天（2 年，含法律诉讼时效）