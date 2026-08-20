package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.DataClassificationAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataClassificationAuditRepository extends BaseMapper<DataClassificationAudit> {
}