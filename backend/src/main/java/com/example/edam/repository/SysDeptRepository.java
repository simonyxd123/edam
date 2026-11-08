package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.SysDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 部门 Repository
 *
 * <p>sys_dept 没有软删列（见 V20260812_1000__init_schema.sql），
 * 所以这里不用 {@code deleted_at IS NULL} 过滤。
 */
@Mapper
public interface SysDeptRepository extends BaseMapper<SysDept> {

    @Select("SELECT * FROM sys_dept WHERE id = #{id}")
    SysDept findById(@Param("id") Long id);

    /**
     * 按部门**名称**查（SSO 的 department 属性映射用）。
     *
     * <p>名称精确匹配。查不到返回 null —— 调用方要能区分
     * "IdP 给了个我们不认识的部门" 和 "IdP 没给部门"。
     */
    @Select("SELECT * FROM sys_dept WHERE name = #{name} LIMIT 1")
    SysDept findByName(@Param("name") String name);

    /**
     * 查某个部门及其所有下级（按物化路径前缀匹配）。
     *
     * <p>用于"整个部门树都算敏感部门"这种判定 —— 子部门继承上级部门的敏感度。
     */
    @Select("SELECT * FROM sys_dept WHERE id = #{id} OR path LIKE CONCAT(#{path}, '%')")
    List<SysDept> findSubtree(@Param("id") Long id, @Param("path") String path);
}
