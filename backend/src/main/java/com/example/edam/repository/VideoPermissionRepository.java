package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.VideoPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 视频授权关联 Repository（表 {@code video_permission}）。
 */
@Mapper
public interface VideoPermissionRepository extends BaseMapper<VideoPermission> {

    /**
     * 取某个用户对某个视频的授权记录（可能有多条，调用方按"最新一条"处理）。
     *
     * <p>唯一键 {@code uk_video_user} 保证 (video_id, user_id) 唯一，
     * 但仍用 List 返回 —— 历史上若有人手工插过重复行，不会直接抛异常。
     */
    @Select("SELECT * FROM video_permission WHERE video_id = #{videoId} AND user_id = #{userId}")
    List<VideoPermission> findByVideoAndUser(@Param("videoId") Long videoId,
                                            @Param("userId") Long userId);

    /**
     * 取某视频的全部授权（管理端展示"谁被授权了"用）。
     */
    @Select("SELECT * FROM video_permission WHERE video_id = #{videoId}")
    List<VideoPermission> findByVideo(@Param("videoId") Long videoId);
}
