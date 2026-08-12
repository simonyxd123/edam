# 数据库 Schema 详细定义文档

> 对应方案书 v3.0 第七章；本文件描述所有核心数据表的字段、类型、约束、索引与外键关系。
> 数据库：MySQL 8.0（业务数据） + Redis Cluster（缓存） + MinIO（对象存储）
> 字符集：utf8mb4 / utf8mb4_unicode_ci
> 时间字段：所有 `*_time` / `*_at` 字段均为 DATETIME(3) UTC

---

## 表目录

| 表名 | 中文说明 | 模块 | 估算行数 |
|---|---|---|---|
| sys_user | 用户表 | 用户与权限 | 万级 |
| sys_role | 角色表 | 用户与权限 | 数十 |
| sys_role_permission | 角色权限关联 | 用户与权限 | 千级 |
| sys_user_role | 用户角色关联 | 用户与权限 | 万级 |
| sys_session | 用户会话 | 用户与权限 | 万级（滚动清理） |
| video_resource | 视频资源 | 资源管理 | 十万级 |
| doc_resource | 文档资源 | 资源管理 | 十万级 |
| file_metadata | 文件元数据 | 资源管理 | 百万级 |
| video_permission | 视频权限关联 | 权限关联 | 百万级 |
| doc_permission | 文档权限关联 | 权限关联 | 百万级 |
| distribution_approval | 外发审批 | 权限关联 | 万级 |
| distribution_approval_decision | 审批决策记录 | 权限关联 | 万级 |
| play_log | 播放日志 | 审计溯源 | 千万级（按月分表） |
| operation_log | 操作日志 | 审计溯源 | 千万级（按月分表） |
| watermark_cache | 水印缓存 | 审计溯源 | 百万级 |
| key_rotation_log | 密钥轮转日志 | 审计溯源 | 千级 |
| driver_status | 驱动心跳 | 终端管控 | 万级 |
| external_doc_view_log | 外发文档访问日志 | 审计溯源 | 十万级 |

---

## 一、用户与权限

### 1.1 sys_user（用户表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | | 主键 |
| username | VARCHAR(64) | UNIQUE, NOT NULL | | 用户名（登录用） |
| password_hash | VARCHAR(255) | NOT NULL | | bcrypt 哈希（cost=12） |
| employee_no | VARCHAR(32) | UNIQUE, NOT NULL | | 工号 |
| real_name | VARCHAR(64) | NOT NULL | | 真实姓名（PII） |
| email | VARCHAR(128) | UNIQUE | NULL | 邮箱 |
| phone | VARCHAR(32) | | NULL | 手机号 |
| dept_id | BIGINT UNSIGNED | NOT NULL, FK→sys_dept.id | | 部门 ID |
| status | TINYINT | NOT NULL | 1 | 1=active 2=disabled 3=locked |
| mfa_secret | VARCHAR(64) | | NULL | TOTP 密钥（加密存储） |
| mfa_enabled | TINYINT(1) | NOT NULL | 0 | 是否启用 MFA |
| last_login_at | DATETIME(3) | | NULL | 最后登录时间 |
| last_login_ip | VARCHAR(45) | | NULL | 最后登录 IP（IPv6 兼容） |
| failed_login_count | TINYINT UNSIGNED | NOT NULL | 0 | 连续失败次数（5 次锁定） |
| created_at | DATETIME(3) | NOT NULL | CURRENT_TIMESTAMP(3) | 创建时间 |
| updated_at | DATETIME(3) | NOT NULL, ON UPDATE | CURRENT_TIMESTAMP(3) | 更新时间 |
| deleted_at | DATETIME(3) | NULL | NULL | 软删除时间 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_username (username)
- UNIQUE KEY uk_employee_no (employee_no)
- UNIQUE KEY uk_email (email)
- KEY idx_dept_id (dept_id)
- KEY idx_status (status)
- KEY idx_last_login_at (last_login_at)

**外键**：
- dept_id → sys_dept.id

**PII 字段**：`real_name`、`email`、`phone`、`mfa_secret`，需加密存储或访问审计。

---

### 1.2 sys_role（角色表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | | 主键 |
| code | VARCHAR(64) | UNIQUE, NOT NULL | | 角色代码（如 dept_manager） |
| name | VARCHAR(128) | NOT NULL | | 角色名称 |
| description | VARCHAR(255) | | NULL | 描述 |
| is_system | TINYINT(1) | NOT NULL | 0 | 系统预置（不可删除） |
| created_at | DATETIME(3) | NOT NULL | | |

**索引**：PRIMARY KEY (id), UNIQUE KEY uk_code (code)

---

### 1.3 sys_role_permission（角色权限关联表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| permission_id | BIGINT UNSIGNED | NOT NULL, FK→sys_permission.id | | 权限 ID |
| role_id | BIGINT UNSIGNED | NOT NULL, FK→sys_role.id | | 角色 ID |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_role_permission (role_id, permission_id)
- KEY idx_permission_id (permission_id)

---

### 1.4 sys_user_role（用户角色关联表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| user_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | |
| role_id | BIGINT UNSIGNED | NOT NULL, FK→sys_role.id | | |
| granted_by | BIGINT UNSIGNED | FK→sys_user.id | NULL | 授权人 |
| granted_at | DATETIME(3) | NOT NULL | | 授权时间 |
| expire_at | DATETIME(3) | | NULL | 过期时间（NULL=永久） |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_user_role (user_id, role_id)
- KEY idx_role_id (role_id)
- KEY idx_expire_at (expire_at)

---

### 1.5 sys_session（用户会话表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| user_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | |
| session_id | CHAR(64) | UNIQUE, NOT NULL | | 会话 UUID |
| refresh_token_hash | CHAR(64) | NOT NULL | | refresh_token SHA-256 |
| ip | VARCHAR(45) | | NULL | |
| user_agent | VARCHAR(512) | | NULL | |
| created_at | DATETIME(3) | NOT NULL | | |
| last_active_at | DATETIME(3) | NOT NULL | | |
| expire_at | DATETIME(3) | NOT NULL | | refresh_token 过期时间 |
| revoked | TINYINT(1) | NOT NULL | 0 | 是否已撤销 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_session_id (session_id)
- KEY idx_user_id (user_id)
- KEY idx_expire_at (expire_at)（定时清理用）

**数据生命周期**：会话 7 天过期，凌晨清理任务删除过期记录。

---

### 1.6 sys_permission（权限定义表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| code | VARCHAR(128) | UNIQUE, NOT NULL | | 权限代码（如 `video:read`） |
| name | VARCHAR(128) | NOT NULL | | 权限名称 |
| resource_type | VARCHAR(32) | NOT NULL | | video / document / system |

**索引**：PRIMARY KEY (id), UNIQUE KEY uk_code (code)

**预置权限代码示例**：
```
video:read, video:download, video:upload, video:delete, video:distribute
document:read, document:download, document:upload, document:delete, document:distribute
document:edit, document:print
system:audit_export, system:user_manage, system:role_manage
```

---

## 二、资源管理

### 2.1 video_resource（视频资源表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| title | VARCHAR(255) | NOT NULL | | 视频标题 |
| description | TEXT | | NULL | |
| file_hash | CHAR(64) | NOT NULL | | SHA-256 文件哈希 |
| minio_path | VARCHAR(512) | NOT NULL | | MinIO 对象路径 |
| duration_sec | INT UNSIGNED | NOT NULL | 0 | 时长（秒） |
| size_bytes | BIGINT UNSIGNED | NOT NULL | | 文件大小 |
| mime_type | VARCHAR(64) | NOT NULL | | video/mp4 等 |
| classification_lv | TINYINT | NOT NULL | 1 | 1=L1 2=L2 3=L3 4=L4 |
| uploader_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | 上传者 |
| upload_time | DATETIME(3) | NOT NULL | | 上传时间 |
| hls_status | TINYINT | NOT NULL | 0 | 0=pending 1=processing 2=ready 3=failed |
| hls_path | VARCHAR(512) | | NULL | HLS 输出路径 |
| fingerprint_status | TINYINT | NOT NULL | 0 | 帧指纹提取状态 |
| fingerprint_path | VARCHAR(512) | | NULL | 帧指纹存储路径 |
| key_id | BIGINT UNSIGNED | FK→key_rotation_log.id | NULL | 当前加密密钥 |
| view_count | BIGINT UNSIGNED | NOT NULL DEFAULT 0 | | 累计观看次数 |
| deleted_at | DATETIME(3) | NULL | NULL | 软删除 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_file_hash (file_hash)
- KEY idx_uploader_id (uploader_id)
- KEY idx_classification_lv (classification_lv)
- KEY idx_upload_time (upload_time)
- KEY idx_hls_status (hls_status)

---

### 2.2 doc_resource（文档资源表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| title | VARCHAR(255) | NOT NULL | | 文档标题 |
| file_type | VARCHAR(16) | NOT NULL | | docx / pdf / xlsx / pptx / image |
| file_hash | CHAR(64) | NOT NULL | | SHA-256 |
| minio_path | VARCHAR(512) | NOT NULL | | |
| preview_path | VARCHAR(512) | | NULL | 转换后预览文件路径（如 PDF 预览） |
| size_bytes | BIGINT UNSIGNED | NOT NULL | | |
| mime_type | VARCHAR(64) | NOT NULL | | |
| classification_lv | TINYINT | NOT NULL | 1 | |
| uploader_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | |
| upload_time | DATETIME(3) | NOT NULL | | |
| watermark_status | TINYINT | NOT NULL | 0 | 0=pending 1=processing 2=ready 3=failed 4=skipped |
| preview_status | TINYINT | NOT NULL | 0 | 0=pending 1=processing 2=ready 3=failed |
| encrypted | TINYINT(1) | NOT NULL DEFAULT 1 | | 是否加密存储 |
| key_id | BIGINT UNSIGNED | FK→key_rotation_log.id | NULL | |
| view_count | BIGINT UNSIGNED | NOT NULL DEFAULT 0 | | |
| deleted_at | DATETIME(3) | NULL | NULL | |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_file_hash (file_hash)
- KEY idx_uploader_id (uploader_id)
- KEY idx_file_type (file_type)
- KEY idx_classification_lv (classification_lv)
- KEY idx_upload_time (upload_time)

---

### 2.3 file_metadata（文件元数据表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| file_hash | CHAR(64) | UNIQUE, NOT NULL | | 全局唯一文件哈希（跨资源类型去重） |
| file_type | VARCHAR(16) | NOT NULL | | 资源类型 video / document |
| mime_type | VARCHAR(64) | NOT NULL | | |
| size_bytes | BIGINT UNSIGNED | NOT NULL | | |
| minio_path | VARCHAR(512) | NOT NULL | | MinIO 物理路径 |
| encryption_key_id | BIGINT UNSIGNED | FK→key_rotation_log.id | NULL | 当前加密密钥 ID |
| encryption_algo | VARCHAR(32) | NOT NULL | | AES-256 / SM4 等 |
| dedup_ref_count | INT UNSIGNED | NOT NULL DEFAULT 1 | | 引用计数（秒传用） |
| created_at | DATETIME(3) | NOT NULL | | |
| last_access_at | DATETIME(3) | | NULL | LRU 冷热分层 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_file_hash (file_hash)
- KEY idx_file_type (file_type)
- KEY idx_last_access_at (last_access_at)

**说明**：用于实现"秒传"功能，同一文件多用户上传时只存储一份。

---

## 三、权限关联

### 3.1 video_permission（视频权限关联表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| video_id | BIGINT UNSIGNED | NOT NULL, FK→video_resource.id | | |
| user_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | |
| actions | TINYINT UNSIGNED | NOT NULL | 1 | 位掩码：1=view 2=download 4=edit 8=delete 16=distribute |
| granted_by | BIGINT UNSIGNED | FK→sys_user.id | NULL | |
| granted_at | DATETIME(3) | NOT NULL | | |
| expire_at | DATETIME(3) | | NULL | 过期时间 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_video_user (video_id, user_id)
- KEY idx_user_id (user_id)
- KEY idx_expire_at (expire_at)

---

### 3.2 doc_permission（文档权限关联表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| doc_id | BIGINT UNSIGNED | NOT NULL, FK→doc_resource.id | | |
| user_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | |
| actions | TINYINT UNSIGNED | NOT NULL | 1 | 位掩码：1=view 2=download 4=edit 8=delete 16=distribute 32=print |
| granted_by | BIGINT UNSIGNED | FK→sys_user.id | NULL | |
| granted_at | DATETIME(3) | NOT NULL | | |
| expire_at | DATETIME(3) | | NULL | |

**索引**：同 video_permission

---

### 3.3 distribution_approval（外发审批表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| doc_id | BIGINT UNSIGNED | NOT NULL, FK→doc_resource.id | | |
| applicant_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | 发起人 |
| external_recipient_name | VARCHAR(128) | NOT NULL | | 外部接收人姓名 |
| external_recipient_email | VARCHAR(128) | NOT NULL | | 外部接收人邮箱 |
| external_recipient_org | VARCHAR(255) | | NULL | 外部组织 |
| reason | TEXT | NOT NULL | | 外发理由 |
| valid_hours | INT UNSIGNED | NOT NULL | | 有效期（小时） |
| max_open_count | INT UNSIGNED | NOT NULL | 5 | 最大打开次数 |
| allow_forward | TINYINT(1) | NOT NULL | 0 | 允许转发 |
| allow_print | TINYINT(1) | NOT NULL | 0 | 允许打印 |
| status | TINYINT | NOT NULL | 0 | 0=pending 1=approved 2=rejected 3=expired 4=revoked |
| current_open_count | INT UNSIGNED | NOT NULL | 0 | 已打开次数 |
| final_decision_at | DATETIME(3) | | NULL | 最终决策时间 |
| created_at | DATETIME(3) | NOT NULL | | |

**索引**：
- PRIMARY KEY (id)
- KEY idx_doc_id (doc_id)
- KEY idx_applicant_id (applicant_id)
- KEY idx_status (status)
- KEY idx_external_email (external_recipient_email)

---

### 3.4 distribution_approval_decision（审批决策记录）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| approval_id | BIGINT UNSIGNED | NOT NULL, FK→distribution_approval.id | | |
| approver_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | 审批人 |
| decision | TINYINT | NOT NULL | | 1=approve 2=reject |
| comment | TEXT | | NULL | |
| decided_at | DATETIME(3) | NOT NULL | | |

**索引**：
- PRIMARY KEY (id)
- KEY idx_approval_id (approval_id)

---

## 四、审计溯源

### 4.1 play_log（播放日志，按月分表）

**分表规则**：play_log_YYYYMM（如 play_log_202608），每月 1 张表，最多保留 12 张。

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| user_id | BIGINT UNSIGNED | NOT NULL | | |
| video_id | BIGINT UNSIGNED | NOT NULL | | |
| session_id | CHAR(64) | NOT NULL | | |
| access_time | DATETIME(3) | NOT NULL | | |
| ip | VARCHAR(45) | | NULL | |
| user_agent | VARCHAR(512) | | NULL | |
| progress_sec | INT UNSIGNED | NOT NULL | 0 | 播放进度 |
| event | VARCHAR(32) | NOT NULL | | start / progress / end / error |
| watermark_aplied | TINYINT(1) | NOT NULL | 0 | Canvas 水印是否应用 |
| fingerprint_extracted | TINYINT(1) | NOT NULL | 0 | 是否嵌入指纹 |

**索引**：
- PRIMARY KEY (id)
- KEY idx_user_video_time (user_id, video_id, access_time)
- KEY idx_access_time (access_time)
- KEY idx_session_id (session_id)

**数据生命周期**：保留 365 天（合规要求），过期迁移至 OSS-Archive。

---

### 4.2 operation_log（操作日志，按月分表）

**分表规则**：operation_log_YYYYMM。

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| user_id | BIGINT UNSIGNED | NOT NULL | | |
| operation_type | VARCHAR(64) | NOT NULL | | login / upload / download / delete 等 |
| resource_type | VARCHAR(32) | NOT NULL | | video / document / system |
| resource_id | BIGINT UNSIGNED | | NULL | |
| ip | VARCHAR(45) | | NULL | |
| user_agent | VARCHAR(512) | | NULL | |
| result | TINYINT | NOT NULL | | 1=success 2=failure 3=denied |
| detail | JSON | | NULL | 详情（如审批理由、错误码） |
| timestamp | DATETIME(3) | NOT NULL | | |

**索引**：
- PRIMARY KEY (id)
- KEY idx_user_time (user_id, timestamp)
- KEY idx_operation_type (operation_type)
- KEY idx_resource (resource_type, resource_id)
- KEY idx_timestamp (timestamp)

**数据生命周期**：保留 180 天，过期清理。

---

### 4.3 watermark_cache（水印缓存表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| resource_id | BIGINT UNSIGNED | NOT NULL | | |
| resource_type | TINYINT | NOT NULL | | 1=video 2=document |
| user_id_hash | CHAR(64) | NOT NULL | | SHA-256(user_id + salt) |
| fingerprint | TEXT | NOT NULL | | 嵌入的水印/指纹内容 |
| minio_path | VARCHAR(512) | NOT NULL | | MinIO 中的水印副本路径 |
| created_at | DATETIME(3) | NOT NULL | | |
| ttl_sec | INT UNSIGNED | NOT NULL | 86400 | 默认 24 小时 |
| hit_count | INT UNSIGNED | NOT NULL DEFAULT 0 | | 缓存命中次数 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_resource_user (resource_id, resource_type, user_id_hash)
- KEY idx_created_at (created_at)（LRU 淘汰）

---

### 4.4 key_rotation_log（密钥轮转日志）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| key_id | VARCHAR(64) | UNIQUE, NOT NULL | | Vault 中的密钥 ID |
| resource_type | VARCHAR(32) | NOT NULL | | hls_aes / doc_aes / driver_master |
| algorithm | VARCHAR(32) | NOT NULL | | AES-256 / SM4 / HMAC-SHA256 |
| key_hash | CHAR(64) | NOT NULL | | 密钥指纹（SHA-256 哈希前 32 字节） |
| status | TINYINT | NOT NULL | | 1=active 2=grace(灰度) 3=retired |
| rotation_time | DATETIME(3) | NOT NULL | | 轮换时间 |
| operator | BIGINT UNSIGNED | FK→sys_user.id | NULL | 操作人（自动轮转为 NULL） |
| grace_expire_at | DATETIME(3) | | NULL | 灰度期到期时间 |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_key_id (key_id)
- KEY idx_rotation_time (rotation_time)
- KEY idx_resource_status (resource_type, status)

**保留策略**：永久保留（仅密文摘要，无敏感信息）。

---

### 4.5 driver_status（驱动心跳表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| user_id | BIGINT UNSIGNED | NOT NULL, FK→sys_user.id | | |
| device_id | CHAR(64) | NOT NULL | | 终端 UUID |
| os_type | VARCHAR(16) | NOT NULL | | windows / macos / linux |
| os_version | VARCHAR(64) | | NULL | |
| driver_version | VARCHAR(32) | NOT NULL | | 驱动版本号 |
| driver_signature | VARCHAR(255) | NOT NULL | | EV 签名指纹 |
| last_heartbeat_at | DATETIME(3) | NOT NULL | | 最后心跳时间 |
| status | TINYINT | NOT NULL | 1 | 1=active 2=offline 3=disabled 4=crashed |
| ip | VARCHAR(45) | | NULL | |

**索引**：
- PRIMARY KEY (id)
- UNIQUE KEY uk_user_device (user_id, device_id)
- KEY idx_last_heartbeat (last_heartbeat_at)
- KEY idx_status (status)

**离线检测**：心跳间隔 5 分钟；离线超 24 小时触发密钥自动吊销。

---

### 4.6 external_doc_view_log（外发文档访问日志）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| approval_id | BIGINT UNSIGNED | NOT NULL, FK→distribution_approval.id | | |
| external_email | VARCHAR(128) | NOT NULL | | |
| access_time | DATETIME(3) | NOT NULL | | |
| ip | VARCHAR(45) | | NULL | |
| user_agent | VARCHAR(512) | | NULL | |
| action | VARCHAR(32) | NOT NULL | | open / view / download / forward_attempt / print_attempt |
| result | TINYINT | NOT NULL | | 1=success 2=denied 3=expired |

**索引**：
- PRIMARY KEY (id)
- KEY idx_approval_id (approval_id)
- KEY idx_external_email (external_email)
- KEY idx_access_time (access_time)

---

## 五、辅助表

### 5.1 sys_dept（部门表）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|---|---|---|---|---|
| id | BIGINT UNSIGNED | PK | | |
| name | VARCHAR(128) | NOT NULL | | 部门名称 |
| parent_id | BIGINT UNSIGNED | | NULL | 上级部门（树形） |
| path | VARCHAR(512) | NOT NULL | | 路径如 `/1/5/12/` |
| level | TINYINT UNSIGNED | NOT NULL | 1 | 层级 |
| sort_order | INT | NOT NULL DEFAULT 0 | | 排序 |

**索引**：PRIMARY KEY (id), KEY idx_parent_id (parent_id), KEY idx_path (path)

---

## 六、数据字典（枚举值）

### 6.1 classification_lv（密级）

| 值 | 名称 | 定义 |
|---|---|---|
| 1 | L1 公开 | 可对外公开 |
| 2 | L2 内部 | 仅限内部员工 |
| 3 | L3 机密 | 限部门/项目组 |
| 4 | L4 绝密 | 限少数核心人员 |

### 6.2 status（sys_user.status）

| 值 | 名称 | 说明 |
|---|---|---|
| 1 | active | 正常 |
| 2 | disabled | 禁用（离职等） |
| 3 | locked | 锁定（密码错误过多） |

### 6.3 hls_status / preview_status / watermark_status

| 值 | 名称 |
|---|---|
| 0 | pending |
| 1 | processing |
| 2 | ready |
| 3 | failed |
| 4 | skipped（仅 watermark_status） |

### 6.4 approval status

| 值 | 名称 |
|---|---|
| 0 | pending |
| 1 | approved |
| 2 | rejected |
| 3 | expired |
| 4 | revoked |

### 6.5 actions 位掩码

| 位 | 值 | 动作 |
|---|---|---|
| 0 | 1 | view |
| 1 | 2 | download |
| 2 | 4 | edit |
| 3 | 8 | delete |
| 4 | 16 | distribute |
| 5 | 32 | print |

---

## 七、分库分表策略

| 表 | 分表策略 | 分表键 | 单表行数上限 |
|---|---|---|---|
| play_log | 按月分表 | YYYYMM | 5000 万 |
| operation_log | 按月分表 | YYYYMM | 5000 万 |
| external_doc_view_log | 按年分表 | YYYY | 1000 万 |

未分库，保留单库多表；如未来行数过亿，按 user_id 哈希分库。

---

## 八、备份与恢复

| 数据 | 备份频率 | 保留时长 | RPO |
|---|---|---|---|
| MySQL 全量 | 每天 02:00 | 30 天 | 24 小时 |
| MySQL 增量 binlog | 实时 | 7 天 | 5 分钟 |
| MinIO | 每天 03:00 | 90 天 | 24 小时 |
| Vault | 实时同步到 Vault Raft 集群 | 永久 | 0 |
| Redis | AOF everysec | 7 天 | 1 秒 |

---

## 九、性能基线

| 表 | 单表行数 | 高频查询 P99 |
|---|---|---|
| sys_user | 1 万 | < 10 ms |
| video_resource | 10 万 | < 30 ms |
| doc_resource | 10 万 | < 30 ms |
| play_log（按月分表） | 5000 万/表 | < 100 ms |
| operation_log（按月分表） | 5000 万/表 | < 100 ms |

---

**文档版本**：v1.0  ·  日期：2026-08-12  ·  作者：Claude Code
**对应方案书**：v3.0 第七章
**配套文档**：`openapi.yaml`、`图3-数据库ER图.png`