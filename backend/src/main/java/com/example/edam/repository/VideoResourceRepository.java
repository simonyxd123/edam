package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.VideoResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VideoResourceRepository extends BaseMapper<VideoResource> {

    @Select("SELECT * FROM video_resource WHERE file_hash = #{fileHash} AND deleted_at IS NULL LIMIT 1")
    VideoResource findByFileHash(String fileHash);

    @Select("SELECT COUNT(*) FROM video_resource WHERE deleted_at IS NULL")
    long countActive();
}