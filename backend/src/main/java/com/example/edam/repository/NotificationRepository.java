package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 站内通知仓储
 */
@Mapper
public interface NotificationRepository extends BaseMapper<Notification> {

    /**
     * 统计未读数（未过期）。
     */
    @Select("""
        SELECT COUNT(*) FROM notification
        WHERE user_id = #{userId} AND is_read = 0
          AND (expires_at IS NULL OR expires_at > NOW())
        """)
    int countUnread(@Param("userId") Long userId);

    /**
     * 标记单条为已读 —— <b>带 user_id 条件</b>，
     * 否则任何人都能把别人的通知标成已读。
     */
    @Update("""
        UPDATE notification SET is_read = 1, read_at = NOW()
        WHERE id = #{notificationId} AND user_id = #{userId}
        """)
    int markRead(@Param("notificationId") Long notificationId, @Param("userId") Long userId);

    /**
     * 全部标记已读（仅自己的）。
     */
    @Update("""
        UPDATE notification SET is_read = 1, read_at = NOW()
        WHERE user_id = #{userId} AND is_read = 0
        """)
    int markAllRead(@Param("userId") Long userId);
}
