package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionRepository extends BaseMapper<SysPermission> {

    @Select("SELECT * FROM sys_permission WHERE code = #{code} LIMIT 1")
    SysPermission findByCode(@Param("code") String code);

    @Select("SELECT * FROM sys_permission ORDER BY resource_type, action")
    List<SysPermission> findAll();

    @Select("SELECT * FROM sys_permission WHERE resource_type = #{resourceType} ORDER BY action")
    List<SysPermission> findByResourceType(@Param("resourceType") String resourceType);
}
