-- ============================================================================
-- V20260812_1000__init_schema.sql
-- 企业全格式数字资产防泄密系统 - 初始化 Schema
-- 数据库：MySQL 8.0
-- 字符集：utf8mb4 / utf8mb4_unicode_ci
-- 引擎：InnoDB
-- 时区：UTC
-- 精度：DATETIME(3) 毫秒
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------------------------------------------------------
-- 一、用户与权限
-- ----------------------------------------------------------------------------

-- 1.1 部门表（树形结构）
CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    parent_id BIGINT UNSIGNED NULL,
    path VARCHAR(512) NOT NULL,
    level TINYINT UNSIGNED NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id),
    KEY idx_path (path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.2 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    employee_no VARCHAR(32) NOT NULL,
    real_name VARCHAR(64) NOT NULL,
    email VARCHAR(128) NULL,
    phone VARCHAR(32) NULL,
    dept_id BIGINT UNSIGNED NOT NULL,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active 2=disabled 3=locked',
    mfa_secret VARCHAR(64) NULL,
    mfa_enabled TINYINT(1) NOT NULL DEFAULT 0,
    last_login_at DATETIME(3) NULL,
    last_login_ip VARCHAR(45) NULL,
    failed_login_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_employee_no (employee_no),
    UNIQUE KEY uk_email (email),
    KEY idx_dept_id (dept_id),
    KEY idx_status (status),
    KEY idx_last_login_at (last_login_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.3 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(255) NULL,
    is_system TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.4 权限定义表
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.5 角色权限关联
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    permission_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.6 用户角色关联
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    granted_by BIGINT UNSIGNED NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expire_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1.7 会话表
CREATE TABLE IF NOT EXISTS sys_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    session_id CHAR(64) NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_active_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expire_at DATETIME(3) NOT NULL,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user_id (user_id),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 二、资源管理
-- ----------------------------------------------------------------------------

-- 2.1 视频资源表
CREATE TABLE IF NOT EXISTS video_resource (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    file_hash CHAR(64) NOT NULL,
    minio_path VARCHAR(512) NOT NULL,
    duration_sec INT UNSIGNED NOT NULL DEFAULT 0,
    size_bytes BIGINT UNSIGNED NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    classification_lv TINYINT NOT NULL DEFAULT 1 COMMENT '1=L1 2=L2 3=L3 4=L4',
    uploader_id BIGINT UNSIGNED NOT NULL,
    upload_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    hls_status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending 1=processing 2=ready 3=failed',
    hls_path VARCHAR(512) NULL,
    fingerprint_status TINYINT NOT NULL DEFAULT 0,
    fingerprint_path VARCHAR(512) NULL,
    key_id BIGINT UNSIGNED NULL,
    view_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    deleted_at DATETIME(3) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_hash (file_hash),
    KEY idx_uploader_id (uploader_id),
    KEY idx_classification_lv (classification_lv),
    KEY idx_upload_time (upload_time),
    KEY idx_hls_status (hls_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2.2 文档资源表
CREATE TABLE IF NOT EXISTS doc_resource (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    file_type VARCHAR(16) NOT NULL COMMENT 'docx/pdf/xlsx/pptx/image',
    file_hash CHAR(64) NOT NULL,
    minio_path VARCHAR(512) NOT NULL,
    preview_path VARCHAR(512) NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    classification_lv TINYINT NOT NULL DEFAULT 1,
    uploader_id BIGINT UNSIGNED NOT NULL,
    upload_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    watermark_status TINYINT NOT NULL DEFAULT 0,
    preview_status TINYINT NOT NULL DEFAULT 0,
    encrypted TINYINT(1) NOT NULL DEFAULT 1,
    key_id BIGINT UNSIGNED NULL,
    view_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    deleted_at DATETIME(3) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_hash (file_hash),
    KEY idx_uploader_id (uploader_id),
    KEY idx_file_type (file_type),
    KEY idx_classification_lv (classification_lv),
    KEY idx_upload_time (upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2.3 文件元数据（秒传）
CREATE TABLE IF NOT EXISTS file_metadata (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_hash CHAR(64) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    minio_path VARCHAR(512) NOT NULL,
    encryption_key_id BIGINT UNSIGNED NULL,
    encryption_algo VARCHAR(32) NOT NULL,
    dedup_ref_count INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_access_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_hash (file_hash),
    KEY idx_file_type (file_type),
    KEY idx_last_access_at (last_access_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 三、权限关联
-- ----------------------------------------------------------------------------

-- 3.1 视频权限
CREATE TABLE IF NOT EXISTS video_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    actions TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '位掩码',
    granted_by BIGINT UNSIGNED NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expire_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_user (video_id, user_id),
    KEY idx_user_id (user_id),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.2 文档权限
CREATE TABLE IF NOT EXISTS doc_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    doc_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    actions TINYINT UNSIGNED NOT NULL DEFAULT 1,
    granted_by BIGINT UNSIGNED NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expire_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_user (doc_id, user_id),
    KEY idx_user_id (user_id),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.3 外发审批
CREATE TABLE IF NOT EXISTS distribution_approval (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    doc_id BIGINT UNSIGNED NOT NULL,
    applicant_id BIGINT UNSIGNED NOT NULL,
    external_recipient_name VARCHAR(128) NOT NULL,
    external_recipient_email VARCHAR(128) NOT NULL,
    external_recipient_org VARCHAR(255) NULL,
    reason TEXT NOT NULL,
    valid_hours INT UNSIGNED NOT NULL,
    max_open_count INT UNSIGNED NOT NULL DEFAULT 5,
    allow_forward TINYINT(1) NOT NULL DEFAULT 0,
    allow_print TINYINT(1) NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending 1=approved 2=rejected 3=expired 4=revoked',
    current_open_count INT UNSIGNED NOT NULL DEFAULT 0,
    final_decision_at DATETIME(3) NULL,
    revoked_by BIGINT UNSIGNED NULL,
    revoked_at DATETIME(3) NULL,
    revoke_reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_doc_id (doc_id),
    KEY idx_applicant_id (applicant_id),
    KEY idx_status (status),
    KEY idx_external_email (external_recipient_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3.4 审批决策记录
CREATE TABLE IF NOT EXISTS distribution_approval_decision (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    approval_id BIGINT UNSIGNED NOT NULL,
    approver_id BIGINT UNSIGNED NOT NULL,
    decision TINYINT NOT NULL COMMENT '1=approve 2=reject',
    comment TEXT NULL,
    decided_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_approval_id (approval_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 四、审计溯源
-- ----------------------------------------------------------------------------

-- 4.1 播放日志（按月分表：play_log_YYYYMM）
CREATE TABLE IF NOT EXISTS play_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    video_id BIGINT UNSIGNED NOT NULL,
    session_id CHAR(64) NOT NULL,
    access_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    progress_sec INT UNSIGNED NOT NULL DEFAULT 0,
    event VARCHAR(32) NOT NULL,
    watermark_applied TINYINT(1) NOT NULL DEFAULT 0,
    fingerprint_extracted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_video_time (user_id, video_id, access_time),
    KEY idx_access_time (access_time),
    KEY idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4.2 操作日志（按月分表：operation_log_YYYYMM）
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id BIGINT UNSIGNED NULL,
    ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    result TINYINT NOT NULL COMMENT '1=success 2=failure 3=denied',
    detail JSON NULL,
    timestamp DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, timestamp),
    KEY idx_operation_type (operation_type),
    KEY idx_resource (resource_type, resource_id),
    KEY idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4.3 水印缓存
CREATE TABLE IF NOT EXISTS watermark_cache (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    resource_id BIGINT UNSIGNED NOT NULL,
    resource_type TINYINT NOT NULL COMMENT '1=video 2=document',
    user_id_hash CHAR(64) NOT NULL,
    fingerprint TEXT NOT NULL,
    minio_path VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ttl_sec INT UNSIGNED NOT NULL DEFAULT 86400,
    hit_count INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_user (resource_id, resource_type, user_id_hash),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4.4 密钥轮转日志
CREATE TABLE IF NOT EXISTS key_rotation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    key_id VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    status TINYINT NOT NULL COMMENT '1=active 2=grace 3=retired',
    rotation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    operator BIGINT UNSIGNED NULL,
    grace_expire_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_id (key_id),
    KEY idx_rotation_time (rotation_time),
    KEY idx_resource_status (resource_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4.5 驱动心跳
CREATE TABLE IF NOT EXISTS driver_status (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    device_id CHAR(64) NOT NULL,
    os_type VARCHAR(16) NOT NULL,
    os_version VARCHAR(64) NULL,
    driver_version VARCHAR(32) NOT NULL,
    driver_signature VARCHAR(255) NOT NULL,
    last_heartbeat_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active 2=offline 3=disabled 4=crashed',
    ip VARCHAR(45) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_device (user_id, device_id),
    KEY idx_last_heartbeat (last_heartbeat_at),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4.6 外发文档访问日志
CREATE TABLE IF NOT EXISTS external_doc_view_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    approval_id BIGINT UNSIGNED NOT NULL,
    external_email VARCHAR(128) NOT NULL,
    access_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    action VARCHAR(32) NOT NULL,
    result TINYINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_approval_id (approval_id),
    KEY idx_external_email (external_email),
    KEY idx_access_time (access_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 五、v3.1 新增表
-- ----------------------------------------------------------------------------

-- 5.1 通知表
CREATE TABLE IF NOT EXISTS notification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NULL,
    related_resource_type VARCHAR(32) NULL,
    related_resource_id BIGINT UNSIGNED NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_user_unread (user_id, is_read, created_at),
    KEY idx_created_at (created_at),
    KEY idx_related (related_resource_type, related_resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.2 通知偏好
CREATE TABLE IF NOT EXISTS notification_preferences (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    channels JSON NOT NULL,
    types JSON NOT NULL,
    quiet_hours_enabled TINYINT(1) NOT NULL DEFAULT 0,
    quiet_hours_start TIME NULL,
    quiet_hours_end TIME NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.3 标签表
CREATE TABLE IF NOT EXISTS tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(32) NOT NULL,
    code VARCHAR(64) NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'both' COMMENT 'video/document/both',
    color VARCHAR(7) NULL,
    use_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.4 视频标签关联
CREATE TABLE IF NOT EXISTS video_tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    video_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_tag (video_id, tag_id),
    KEY idx_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.5 文档标签关联
CREATE TABLE IF NOT EXISTS doc_tag (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    doc_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_tag (doc_id, tag_id),
    KEY idx_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.6 Webhook 注册
CREATE TABLE IF NOT EXISTS webhook (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id BIGINT UNSIGNED NOT NULL,
    url VARCHAR(512) NOT NULL,
    events VARCHAR(512) NOT NULL,
    secret_hash CHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active 2=paused 3=failed',
    last_delivered_at DATETIME(3) NULL,
    fail_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_owner_id (owner_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.7 Webhook 投递历史
CREATE TABLE IF NOT EXISTS webhook_delivery (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    webhook_id BIGINT UNSIGNED NOT NULL,
    event VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    response_status INT NULL,
    response_body TEXT NULL,
    delivered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    retry_count TINYINT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_webhook_delivered (webhook_id, delivered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5.8 备份元数据
CREATE TABLE IF NOT EXISTS backup (
    id VARCHAR(64) NOT NULL,
    type TINYINT NOT NULL COMMENT '1=full 2=incremental',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=pending 1=running 2=completed 3=failed',
    size_bytes BIGINT UNSIGNED NULL,
    storage_path VARCHAR(512) NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    operator_id BIGINT UNSIGNED NULL,
    description VARCHAR(255) NULL,
    checksum CHAR(64) NULL,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- 外键约束（v3.1：在所有表创建后统一加）
-- ----------------------------------------------------------------------------
-- 注意：外键约束会影响性能与高可用；如需要横向扩展可禁用
ALTER TABLE sys_user ADD CONSTRAINT fk_user_dept FOREIGN KEY (dept_id) REFERENCES sys_dept(id);
ALTER TABLE sys_role_permission ADD CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id);
ALTER TABLE sys_role_permission ADD CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission(id);
ALTER TABLE sys_user_role ADD CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id);
ALTER TABLE sys_user_role ADD CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id);
ALTER TABLE sys_session ADD CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id);

ALTER TABLE video_resource ADD CONSTRAINT fk_video_uploader FOREIGN KEY (uploader_id) REFERENCES sys_user(id);
ALTER TABLE doc_resource ADD CONSTRAINT fk_doc_uploader FOREIGN KEY (uploader_id) REFERENCES sys_user(id);

ALTER TABLE video_permission ADD CONSTRAINT fk_vp_video FOREIGN KEY (video_id) REFERENCES video_resource(id);
ALTER TABLE video_permission ADD CONSTRAINT fk_vp_user FOREIGN KEY (user_id) REFERENCES sys_user(id);
ALTER TABLE doc_permission ADD CONSTRAINT fk_dp_doc FOREIGN KEY (doc_id) REFERENCES doc_resource(id);
ALTER TABLE doc_permission ADD CONSTRAINT fk_dp_user FOREIGN KEY (user_id) REFERENCES sys_user(id);

ALTER TABLE distribution_approval ADD CONSTRAINT fk_da_doc FOREIGN KEY (doc_id) REFERENCES doc_resource(id);
ALTER TABLE distribution_approval ADD CONSTRAINT fk_da_applicant FOREIGN KEY (applicant_id) REFERENCES sys_user(id);
ALTER TABLE distribution_approval_decision ADD CONSTRAINT fk_dad_approval FOREIGN KEY (approval_id) REFERENCES distribution_approval(id);

ALTER TABLE driver_status ADD CONSTRAINT fk_driver_user FOREIGN KEY (user_id) REFERENCES sys_user(id);
ALTER TABLE external_doc_view_log ADD CONSTRAINT fk_edvl_approval FOREIGN KEY (approval_id) REFERENCES distribution_approval(id);

ALTER TABLE notification ADD CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES sys_user(id);
ALTER TABLE notification_preferences ADD CONSTRAINT fk_np_user FOREIGN KEY (user_id) REFERENCES sys_user(id);

ALTER TABLE video_tag ADD CONSTRAINT fk_vt_video FOREIGN KEY (video_id) REFERENCES video_resource(id);
ALTER TABLE video_tag ADD CONSTRAINT fk_vt_tag FOREIGN KEY (tag_id) REFERENCES tag(id);
ALTER TABLE doc_tag ADD CONSTRAINT fk_dt_doc FOREIGN KEY (doc_id) REFERENCES doc_resource(id);
ALTER TABLE doc_tag ADD CONSTRAINT fk_dt_tag FOREIGN KEY (tag_id) REFERENCES tag(id);

ALTER TABLE webhook ADD CONSTRAINT fk_wh_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id);
ALTER TABLE webhook_delivery ADD CONSTRAINT fk_whd_webhook FOREIGN KEY (webhook_id) REFERENCES webhook(id);

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 初始化完成
-- 表总数：26 张（不含分表）
-- ============================================================================