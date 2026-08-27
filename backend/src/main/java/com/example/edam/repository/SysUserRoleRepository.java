package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysRole;
import com.example.edam.model.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleRepository extends BaseMapper<SysUserRole> {

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> findRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 查用户的所有角色（已过滤软删）
     */
    @Select("""
        SELECT r.* FROM sys_role r
        JOIN sys_user_role ur ON ur.role_id = r.id
        WHERE ur.user_id = #{userId} AND r.deleted_at IS NULL
    """)
    List<SysRole> findRolesByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
