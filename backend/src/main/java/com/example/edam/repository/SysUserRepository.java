package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserRepository extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE employee_no = #{employeeNo} AND deleted_at IS NULL")
    SysUser findByEmployeeNo(String employeeNo);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted_at IS NULL")
    SysUser findById(Long id);
}