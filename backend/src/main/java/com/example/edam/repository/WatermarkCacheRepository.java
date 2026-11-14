package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.WatermarkCache;
import org.springframework.stereotype.Repository;

/**
 * 水印指纹登记表（{@code watermark_cache}）的仓库。
 */
@Repository
public interface WatermarkCacheRepository extends BaseMapper<WatermarkCache> {
}
