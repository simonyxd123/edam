package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.VideoTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频 ↔ 标签关联 Repository（表 {@code video_tag}）。
 */
@Mapper
public interface VideoTagRepository extends BaseMapper<VideoTag> {
}
