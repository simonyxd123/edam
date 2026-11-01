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

    /**
     * 某部门的全部在职用户。
     *
     * <p>用于 L3「同部门可见」的判定：把同部门同事的 id 查出来，
     * 视频列表就能用 {@code uploader_id IN (...)} 表达"同部门可见"。
     */
    @Select("SELECT * FROM sys_user WHERE dept_id = #{deptId} AND deleted_at IS NULL")
    java.util.List<SysUser> findListByDeptId(Long deptId);
}