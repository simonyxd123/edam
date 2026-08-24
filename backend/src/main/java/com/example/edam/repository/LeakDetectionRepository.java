package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.LeakDetection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeakDetectionRepository extends BaseMapper<LeakDetection> {

    @Select("SELECT * FROM leak_detection WHERE detection_id = #{detectionId} LIMIT 1")
    LeakDetection findByDetectionId(String detectionId);

    /** 按主键 id 查询（MyBatis-Plus BaseMapper.selectById 已提供，但显式声明便于 service 调用） */
    default LeakDetection findById(Long id) {
        return selectById(id);
    }
}