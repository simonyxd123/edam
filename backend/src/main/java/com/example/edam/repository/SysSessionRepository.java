package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话 Repository（v3.2 V-8）
 */
@Mapper
public interface SysSessionRepository extends BaseMapper<SysSession> {

    /**
     * 通过 refresh_token hash 查找
     */
    @Select("SELECT * FROM sys_session WHERE refresh_token_hash = #{hash} AND revoked = 0 AND expire_at > NOW() LIMIT 1")
    SysSession findByRefreshTokenHash(String hash);

    /**
     * 通过 session_id 查找
     */
    @Select("SELECT * FROM sys_session WHERE session_id = #{sessionId} LIMIT 1")
    SysSession findBySessionId(String sessionId);

    /**
     * 查找用户的所有活跃会话
     */
    @Select("SELECT * FROM sys_session WHERE user_id = #{userId} AND revoked = 0 AND expire_at > NOW() ORDER BY last_active_at DESC")
    List<SysSession> findActiveByUserId(Long userId);

    /**
     * 撤销用户的所有会话（紧急吊销）
     */
    @Update("UPDATE sys_session SET revoked = 1, revoked_at = NOW(), revoked_reason = #{reason}, version = version + 1 WHERE user_id = #{userId} AND revoked = 0")
    int revokeAllByUserId(Long userId, String reason);

    /**
     * 清理过期会话（凌晨 cron 调用）
     */
    @Update("DELETE FROM sys_session WHERE expire_at < #{before}")
    int cleanupExpired(LocalDateTime before);

    /**
     * 更新最后活跃时间
     */
    @Update("UPDATE sys_session SET last_active_at = NOW() WHERE session_id = #{sessionId}")
    int touchLastActive(String sessionId);
}