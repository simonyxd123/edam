package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysPasswordHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPasswordHistoryRepository extends BaseMapper<SysPasswordHistory> {

    /**
     * 获取用户最近 N 次密码 hash（按时间倒序）
     */
    @Select("SELECT password_hash FROM sys_password_history WHERE user_id = #{userId} ORDER BY changed_at DESC LIMIT #{limit}")
    List<String> findRecentHashes(Long userId, int limit);
}