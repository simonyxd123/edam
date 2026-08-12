package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.DocResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocResourceRepository extends BaseMapper<DocResource> {

    @Select("SELECT * FROM doc_resource WHERE file_hash = #{fileHash} AND deleted_at IS NULL LIMIT 1")
    DocResource findByFileHash(String fileHash);
}