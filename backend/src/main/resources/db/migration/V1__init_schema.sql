-- ===================================================================
-- EDAM 数据库初始 Schema（Flyway V1）
-- 对应 backend/src/main/java/com/example/edam/model/*.java 中所有 @TableName
-- ===================================================================

-- 字符集
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. 用户与权限 ============================================================

CREATE TABLE sys_user (
  id                BIGINT          NOT NULL AUTO_INCREMENT,
  username          VARCHAR(64)     NOT NULL,
  password_hash     VARCHAR(255)    NOT NULL,
  employee_no       VARCHAR(64)     NOT NULL,
  real_name         VARCHAR(64),
  email             VARCHAR(128),
  phone             VARCHAR(32),
  dept_id           BIGINT,
  status            INT             NOT NULL DEFAULT 1,        -- 1=active 2=disabled 3=locked
  mfa_secret        VARCHAR(255),
  mfa_enabled       INT             NOT NULL DEFAULT 0,
  last_login_at     DATETIME(3),
  last_login_ip     VARCHAR(64),
  failed_login_count INT            NOT NULL DEFAULT 0,
  password_changed_at DATETIME(3),
  must_change_password TINYINT(1)   NOT NULL DEFAULT 0,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at        DATETIME(3),
  version           INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_user_username (username),
  UNIQUE KEY uk_sys_user_employee_no (employee_no),
  KEY idx_sys_user_dept (dept_id),
  KEY idx_sys_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

CREATE TABLE sys_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  code        VARCHAR(64)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  is_system   TINYINT(1)   NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at  DATETIME,
  version     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色';

CREATE TABLE sys_role_permission (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  role_id     BIGINT       NOT NULL,
  resource    VARCHAR(64)  NOT NULL,    -- videos / documents / audit / ...
  action      VARCHAR(32)  NOT NULL,    -- read / write / delete / approve
  constraint_def VARCHAR(255),
  PRIMARY KEY (id),
  KEY idx_srp_role (role_id),
  KEY idx_srp_resource_action (resource, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限映射';

CREATE TABLE sys_session (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  user_id               BIGINT       NOT NULL,
  session_id            VARCHAR(64)  NOT NULL,
  refresh_token_hash    VARCHAR(255) NOT NULL,
  access_token_jti      VARCHAR(64),
  ip                    VARCHAR(64),
  user_agent            VARCHAR(512),
  device_fingerprint    VARCHAR(128),
  login_method          VARCHAR(32),
  created_at                DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_active_at            DATETIME,
  expire_at                 DATETIME,
  access_token_expire_at    DATETIME,
  revoked               TINYINT(1)   NOT NULL DEFAULT 0,
  revoked_at            DATETIME,
  revoked_reason        VARCHAR(255),
  version               INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_session_id (session_id),
  KEY idx_sys_session_user (user_id),
  KEY idx_sys_session_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录会话';

CREATE TABLE sys_password_history (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  changed_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  changed_by    BIGINT,
  change_reason VARCHAR(32),
  PRIMARY KEY (id),
  KEY idx_sys_pwd_hist_user (user_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密码历史';

-- 2. 资源表 ================================================================

CREATE TABLE video_resource (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  title                VARCHAR(255) NOT NULL,
  description         TEXT,
  file_hash           VARCHAR(128),
  minio_path          VARCHAR(512),
  duration_sec        BIGINT,
  size_bytes          BIGINT,
  mime_type           VARCHAR(64),
  classification_lv   INT,                                  -- 1=L1..4=L4
  uploader_id         BIGINT,
  upload_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  hls_status          INT          NOT NULL DEFAULT 0,       -- 0=pending 1=processing 2=ready 3=failed
  hls_path            VARCHAR(512),
  fingerprint_status  INT          NOT NULL DEFAULT 0,
  fingerprint_path    VARCHAR(512),
  key_id              BIGINT,
  view_count          BIGINT       NOT NULL DEFAULT 0,
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at          DATETIME(3),
  version             INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_video_uploader (uploader_id),
  KEY idx_video_classification (classification_lv),
  KEY idx_video_upload_time (upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频资源';

CREATE TABLE doc_resource (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  title             VARCHAR(255) NOT NULL,
  file_type         VARCHAR(32),
  file_hash         VARCHAR(128),
  minio_path        VARCHAR(512),
  preview_path      VARCHAR(512),
  size_bytes        BIGINT,
  mime_type         VARCHAR(64),
  classification_lv INT,
  uploader_id       BIGINT,
  upload_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  watermark_status  INT          NOT NULL DEFAULT 0,        -- 0=pending 1=processing 2=ready 3=failed 4=skipped
  preview_status    INT          NOT NULL DEFAULT 0,
  encrypted         INT          NOT NULL DEFAULT 0,        -- 0/1
  key_id            BIGINT,
  view_count        BIGINT       NOT NULL DEFAULT 0,
  deleted_at        DATETIME(3),
  version           INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_doc_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档资源';

CREATE TABLE video_permission (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  video_id    BIGINT       NOT NULL,
  user_id     BIGINT,
  dept_id     BIGINT,
  role_code   VARCHAR(64),
  permission  VARCHAR(32)  NOT NULL,                       -- view / download / share
  granted_by  BIGINT,
  granted_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at  DATETIME,
  PRIMARY KEY (id),
  KEY idx_vp_video (video_id),
  KEY idx_vp_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频权限';

CREATE TABLE doc_permission (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  doc_id      BIGINT       NOT NULL,
  user_id     BIGINT,
  dept_id     BIGINT,
  role_code   VARCHAR(64),
  permission  VARCHAR(32)  NOT NULL,
  granted_by  BIGINT,
  granted_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at  DATETIME,
  PRIMARY KEY (id),
  KEY idx_dp_doc (doc_id),
  KEY idx_dp_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档权限';

-- 3. 运行日志与缓存 ========================================================

CREATE TABLE play_log (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  video_id        BIGINT       NOT NULL,
  user_id         BIGINT       NOT NULL,
  session_id      VARCHAR(64),
  employee_no     VARCHAR(64),
  event           VARCHAR(32)  NOT NULL,                    -- start / pause / resume / stop / progress
  progress_sec    INT,
  ip              VARCHAR(64),
  user_agent      VARCHAR(512),
  watermark_id    VARCHAR(128),
  ts              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_play_log_video (video_id),
  KEY idx_play_log_user (user_id),
  KEY idx_play_log_ts (ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='播放日志';

CREATE TABLE watermark_cache (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  cache_key    VARCHAR(255) NOT NULL,
  resource_id  BIGINT,
  resource_type VARCHAR(32),
  watermark_data MEDIUMBLOB,
  format       VARCHAR(16),
  size_bytes   BIGINT,
  ttl_seconds  INT          NOT NULL DEFAULT 86400,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expire_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_watermark_cache_key (cache_key),
  KEY idx_watermark_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='水印缓存';

CREATE TABLE operation_log (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  user_id         BIGINT,
  operation_type  VARCHAR(64),
  resource_type   VARCHAR(64),
  resource_id     BIGINT,
  ip              VARCHAR(64),
  user_agent      VARCHAR(512),
  result          INT,                                      -- 1=success 2=failure 3=denied
  detail          TEXT,
  timestamp       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_oplog_user_ts (user_id, timestamp),
  KEY idx_oplog_type_ts (operation_type, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

-- 4. 高级特性表 ============================================================

CREATE TABLE data_classification_audit (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  resource_type   VARCHAR(32)  NOT NULL,                    -- video / document
  resource_id     BIGINT       NOT NULL,
  old_classification VARCHAR(16),
  new_classification VARCHAR(16),
  change_reason   VARCHAR(512),
  changed_by      BIGINT,
  change_method   VARCHAR(16),                              -- auto / manual
  rule_id         BIGINT,
  changed_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dca_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密级变更审计';

CREATE TABLE leak_detection (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  detection_id        VARCHAR(64)  NOT NULL,
  resource_type       VARCHAR(32),
  resource_id         BIGINT,
  leaked_file_path    VARCHAR(512),
  leaked_file_hash    VARCHAR(128),
  detection_method    VARCHAR(32),                          -- phash / dct / manual
  matched_user_id     BIGINT,
  matched_session_id  VARCHAR(64),
  match_score         DOUBLE,
  matched_frames      INT,
  total_frames        INT,
  status              VARCHAR(32),                          -- pending / confirmed / dismissed
  detected_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at         DATETIME,
  reviewed_by         BIGINT,
  review_note         VARCHAR(512),
  PRIMARY KEY (id),
  UNIQUE KEY uk_leak_detection_id (detection_id),
  KEY idx_leak_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='泄露检测';

CREATE TABLE distribution_approval (
  id                          BIGINT       NOT NULL AUTO_INCREMENT,
  doc_id                      BIGINT       NOT NULL,
  applicant_id                BIGINT       NOT NULL,
  external_recipient_name     VARCHAR(128),
  external_recipient_email    VARCHAR(128),
  external_recipient_org      VARCHAR(128),
  reason                      VARCHAR(512),
  valid_hours                 INT,
  max_open_count              INT,
  allow_forward               TINYINT(1)  NOT NULL DEFAULT 0,
  allow_print                 TINYINT(1)  NOT NULL DEFAULT 0,
  status                      INT          NOT NULL DEFAULT 0,  -- 0=pending 1=approved 2=rejected 3=expired 4=revoked
  current_open_count          INT          NOT NULL DEFAULT 0,
  final_decision_at           DATETIME(3),
  revoked_by                  BIGINT,
  revoked_at                  DATETIME(3),
  revoke_reason               VARCHAR(255),
  created_at                  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  version                     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_da_doc (doc_id),
  KEY idx_da_applicant (applicant_id),
  KEY idx_da_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外发审批';

CREATE TABLE webhook_delivery (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  webhook_id      BIGINT       NOT NULL,
  event           VARCHAR(64)  NOT NULL,
  payload         MEDIUMTEXT,
  response_status INT,
  response_body   VARCHAR(1024),
  delivered_at    DATETIME(6)  NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_wd_webhook (webhook_id, delivered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Webhook 投递记录';

CREATE TABLE webauthn_credential (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  user_id           BIGINT       NOT NULL,
  credential_id     VARCHAR(255) NOT NULL,
  public_key        VARCHAR(1024) NOT NULL,
  counter           BIGINT       NOT NULL DEFAULT 0,
  aaguid            VARCHAR(64),
  credential_type   VARCHAR(32),
  user_verification VARCHAR(32),
  backup_eligible   TINYINT(1)   NOT NULL DEFAULT 0,
  backup_state      TINYINT(1)   NOT NULL DEFAULT 0,
  name              VARCHAR(128),
  last_used_at      DATETIME,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked           TINYINT(1)   NOT NULL DEFAULT 0,
  revoked_at        DATETIME,
  revoked_reason    VARCHAR(255),
  PRIMARY KEY (id),
  UNIQUE KEY uk_wc_credential_id (credential_id),
  KEY idx_wc_user (user_id, revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebAuthn 凭据';

-- 6. 预置角色（admin / dept_manager / employee） ===========================
INSERT INTO sys_role (code, name, description, is_system) VALUES
  ('admin',         '系统管理员', '全部权限',                   1),
  ('dept_manager',  '部门管理员', '本部门资源管理 + 审批',       1),
  ('employee',      '普通员工',   '查看 / 下载本部门资源',       1),
  ('auditor',       '审计员',     '只读审计日志',                1);

-- 7. admin 权限映射（all resource, all action） ============================
INSERT INTO sys_role_permission (role_id, resource, action)
SELECT r.id, '*', '*' FROM sys_role r WHERE r.code = 'admin';