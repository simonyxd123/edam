package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.PlayLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 播放日志（play_log）Repository。
 *
 * <p>方案书 §7.1 核心数据表 —— 播放日志表(play_log)。
 */
@Mapper
public interface PlayLogRepository extends BaseMapper<PlayLog> {
}
