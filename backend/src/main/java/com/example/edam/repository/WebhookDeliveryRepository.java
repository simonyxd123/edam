package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.WebhookDelivery;
import org.apache.ibatis.annotations.Mapper;

/**
 * Webhook 投递记录仓储
 */
@Mapper
public interface WebhookDeliveryRepository extends BaseMapper<WebhookDelivery> {
}