-- ============================================================================
-- V20260828_1000__add_data_classification.sql
-- v3.3 W-7：数据分类分级 + 审计
-- =========================================================================：SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. data_classification_audit：分类变更审计
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_classification_audit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    resource_type VARCHAR(16) NOT NULL COMMENT 'video / document',
    resource_id BIGINT UNSIGNED NOT NULL,
    old_classification VARCHAR(8) NULL COMMENT '旧密级 L1-L4',
    new_classification VARCHAR(8) NOT NULL COMMENT '新密级 L1-L4',
    change_reason VARCHAR(512) NULL COMMENT '变更原因',
    changed_by BIGINT UNSIGNED NULL COMMENT '变更人（系统/用户）',
    change_method VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT 'auto / manual / rule',
    rule_id BIGINT UNSIGNED NULL COMMENT '触发的规则 ID',
    changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_resource (resource_type, resource_id, changed_at),
    KEY idx_user (changed_by, changed_at),
    KEY idx_classification (new_classification, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='数据分类变更审计（v3.3 W-7.4）';

-- 数据生命周期：保留 730 天（2 年诉讼时效）

-- ----------------------------------------------------------------------------
-- 2. data_classification_rule：自动分类规则（可配置）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_classification_rule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL COMMENT 'keyword / size / dept / mime / pattern',
    pattern VARCHAR(512) NOT NULL COMMENT '正则表达式 / 文件大小阈值 / 部门代码',
    target_classification VARCHAR(8) NOT NULL COMMENT '目标密级 L1-L4',
    priority INT NOT NULL DEFAULT 100 COMMENT '优先级（数字越小越高）',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    description VARCHAR(255) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_name (rule_name),
    KEY idx_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='数据分类规则（可配置）';

-- 预置规则（V-7.2 阶段）
INSERT INTO data_classification_rule (rule_name, rule_type, pattern, target_classification, priority, description, created_by) VALUES
('L4-核武器/军工', 'keyword', '(?i)(绝密|最高机密|核武器|军工|涉密|top[_-]?secret|nuclear|weapons?)', 'L4', 10, 'L4 绝密关键词', 1),
('L3-合同/法务', 'keyword', '(?i)(合同|合规|审计|法务|人事|薪酬|contract|compliance|audit|legal)', 'L3', 20, 'L3 机密关键词', 1),
('L3-未公开/内幕', 'keyword', '(?i)(机密|核心|战略|股权|并购|未公开|内幕|confidential|secret|merger|acquisition|insider)', 'L3', 21, 'L3 机密关键词2', 1),
('L2-内部/项目', 'keyword', '(?i)(内部|项目|roadmap|规划|周报|月报|会议纪要|internal|product)', 'L2', 30, 'L2 内部关键词', 1),
('L3-部门-HR', 'dept', 'hr|legal|audit|法务|审计', 'L3', 40, '敏感部门', 1),
('L2-部门-财务', 'dept', 'finance|财务', 'L2', 41, '财务部门', 1),
('L4-超大文件', 'size', '>5GB', 'L4', 50, '超大文件', 1),
('L3-大文件', 'size', '>1GB', 'L3', 51, '大文件', 1),
('L2-中等文件', 'size', '>100MB', 'L2', 52, '中等文件', 1);

-- ----------------------------------------------------------------------------
-- 3. sys_user 增加默认密级字段（用户上传时默认按部门）
-- ----------------------------------------------------------------------------
ALTER TABLE sys_user
    ADD COLUMN default_classification VARCHAR(8) NOT NULL DEFAULT 'L1' COMMENT '用户上传默认密级（按部门）' AFTER must_change_password;