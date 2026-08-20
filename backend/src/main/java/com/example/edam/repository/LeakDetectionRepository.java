package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.LeakDetection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeakDetectionRepository extends BaseMapper<LeakDetection> {

    @Select("SELECT * FROM leak_detection WHERE detection_id = #{detectionId} LIMIT 1")
    LeakDetection findByDetectionId(String detectionId);
}