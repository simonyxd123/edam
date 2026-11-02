package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.DocPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档授权关联 Repository（表 {@code doc_permission}）。
 */
@Mapper
public interface DocPermissionRepository extends BaseMapper<DocPermission> {

    /**
     * 某用户对某文档的授权记录。
     *
     * <p>唯一键 {@code uk_doc_user} 保证 (doc_id, user_id) 唯一，
     * 仍用 List 返回 —— 历史脏数据不该直接抛异常。
     */
    @Select("SELECT * FROM doc_permission WHERE doc_id = #{docId} AND user_id = #{userId}")
    List<DocPermission> findByDocAndUser(@Param("docId") Long docId,
                                         @Param("userId") Long userId);

    /**
     * 某文档的全部授权（管理端展示"谁被授权了"用）。
     */
    @Select("SELECT * FROM doc_permission WHERE doc_id = #{docId}")
    List<DocPermission> findByDoc(@Param("docId") Long docId);
}
