package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 视频资源实体
 * 对应表：video_resource（参考 database_schema.md 2.1）
 */
@Data
@TableName("video_resource")
public class VideoResource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String description;
    private String fileHash;
    private String minioPath;
    private Long durationSec;
    private Long sizeBytes;
    private String mimeType;

    /** 1=L1 2=L2 3=L3 4=L4 */
    private Integer classificationLv;

    private Long uploaderId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime uploadTime;

    /** 0=pending 1=processing 2=ready 3=failed */
    private Integer hlsStatus;
    private String hlsPath;
    private Integer fingerprintStatus;
    private String fingerprintPath;
    private Long keyId;
    private Long viewCount;

    @TableLogic
    private OffsetDateTime deletedAt;

    @Version
    private Integer version;
}