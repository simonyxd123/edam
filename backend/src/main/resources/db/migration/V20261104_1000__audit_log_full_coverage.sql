-- V20261103_1000：operation_log 扩展为"全操作审计"
-- ===================================================================
-- 背景：审计日志只覆盖了极少数操作（登录、预览、角色/用户增删改），
-- 列表查询、查看详情、上传、下载、授权撤权、标签/Webhook 变更、
-- 播放心跳、审计导出……全都没有记录。而 result 列本来就设计了
-- 1=success / 2=failure / 3=denied 三态，实际只写过 success ——
-- **越权尝试与失败操作一条不落地留痕**。
--
-- 本次变更做三件事：
--   1. user_id 改可空 —— 现在登录失败时 user_id 是未知的（写的是 NULL），
--      而列是 NOT NULL 且没有默认值 ⇒ MySQL 直接拒绝这条 INSERT
--      ⇒ **失败登录的审计记录从来没写进过库**。
--      （MyBatis-Plus 的默认 insert 策略是 NOT_NULL，null 字段压根不出现在
--        INSERT 语句里，于是走 MySQL 的 "字段无默认值" 报错。）
--   2. 补齐排障所需的列：http_method / endpoint / status_code /
--      duration_ms / session_id。
--   3. 补两个索引：按端点查、按资源类型 + 时间查。
--
-- 方案书对应章节：
--   §7.1 核心数据表 —— 审计与溯源：播放日志表(play_log)、操作日志表(operation_log)
--   §7.3 数据治理 —— 生命周期：操作日志保留 180 天，播放日志保留 365 天
--   §14.1 资产识别 —— 审计日志：操作日志、播放日志、外发审批记录
--   §14.3 STRIDE —— Repudiation（否认）：员工否认操作行为 → 操作日志全审计
--
-- 兼容性：新增列全部可空 ⇒ 存量行不受影响；
--         user_id 改可空是**放宽**约束，不会让已有数据失效。
-- ===================================================================

ALTER TABLE operation_log
    MODIFY COLUMN user_id BIGINT UNSIGNED NULL
        COMMENT '操作者 user_id；登录失败等"操作者未知"的场景为 NULL',

    ADD COLUMN http_method VARCHAR(10) NULL
        COMMENT 'HTTP 方法（GET/POST/PUT/PATCH/DELETE）' AFTER user_agent,

    ADD COLUMN endpoint VARCHAR(255) NULL
        COMMENT '命中的路由模板，如 /videos/{video_id}（不含真实 ID，便于按接口聚合）' AFTER http_method,

    ADD COLUMN status_code INT NULL
        COMMENT 'HTTP 响应状态码；异常路径取异常自带的状态码' AFTER endpoint,

    ADD COLUMN duration_ms INT NULL
        COMMENT '服务端处理耗时（毫秒）' AFTER status_code,

    ADD COLUMN session_id VARCHAR(64) NULL
        COMMENT '会话 ID（JWT claims.session_id），用于把一次登录串起来的多次操作' AFTER duration_ms,

    ADD KEY idx_endpoint (endpoint),
    ADD KEY idx_resource_time (resource_type, timestamp);
