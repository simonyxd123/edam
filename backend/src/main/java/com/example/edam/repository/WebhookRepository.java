package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.Webhook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 回调注册仓储
 */
@Mapper
public interface WebhookRepository extends BaseMapper<Webhook> {

    /**
     * 查某人的全部回调（按创建时间倒序）。
     */
    @Select("SELECT * FROM webhook WHERE owner_id = #{ownerId} ORDER BY created_at DESC")
    List<Webhook> findByOwner(@Param("ownerId") Long ownerId);

    /**
     * 按 id + owner 查 —— **必须带 owner_id**，
     * 否则任何人都能读 / 改别人的回调配置。
     */
    @Select("SELECT * FROM webhook WHERE id = #{id} AND owner_id = #{ownerId}")
    Webhook findByIdAndOwner(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
