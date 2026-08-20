package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Repository（v3.2 V-5）
 *
 * @TableLogic 自动处理软删除（deleted_at IS NULL）
 */
@Mapper
public interface SysRoleRepository extends BaseMapper<SysRole> {
}