package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.NotificationPreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知偏好仓储
 */
@Mapper
public interface NotificationPreferenceRepository extends BaseMapper<NotificationPreference> {

    @Select("SELECT * FROM notification_preferences WHERE user_id = #{userId}")
    NotificationPreference findByUserId(@Param("userId") Long userId);
}
