package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色 Repository（v3.2 V-5）
 *
 * @TableLogic 自动处理软删除（deleted_at IS NULL）
 */
@Mapper
public interface SysRoleRepository extends BaseMapper<SysRole> {

    /**
     * 按角色 code 查角色（已过滤软删）。
     *
     * <p>code 是 RBAC 的对外标识（{@code admin} / {@code employee} …），
     * 配置文件与外部系统按 code 引用角色，DB 里按 id 关联。
     */
    @Select("SELECT * FROM sys_role WHERE code = #{code} AND deleted_at IS NULL LIMIT 1")
    SysRole findByCode(@Param("code") String code);
}