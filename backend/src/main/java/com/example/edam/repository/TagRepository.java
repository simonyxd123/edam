package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.Tag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标签 Repository（表 {@code tag}）。
 */
@Mapper
public interface TagRepository extends BaseMapper<Tag> {
}
