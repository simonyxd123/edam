package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 视频 ↔ 标签关联（表 {@code video_tag}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TableName("video_tag")
public class VideoTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private Long tagId;
}
