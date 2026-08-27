package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysRolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRolePermissionRepository extends BaseMapper<SysRolePermission> {

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> findPermissionIdsByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId} AND permission_id = #{permissionId}")
    int deleteByRoleAndPermission(@Param("roleId") Long roleId,
                                 @Param("permissionId") Long permissionId);
}
