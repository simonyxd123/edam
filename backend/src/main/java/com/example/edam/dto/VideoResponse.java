package com.example.edam.dto;

import com.example.edam.model.VideoResource;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 视频资源对外 DTO。
 *
 * <p>与 {@link VideoResource} 的区别：状态 / 密级字段已从 Integer
 * 映射为字符串（见 {@link ResourceEnums}），前端可以直接比较。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class VideoResponse {

    private Long id;
    private String title;
    private String description;
    private String fileHash;
    private String mimeType;
    private Long durationSec;
    private Long sizeBytes;

    /** L1 / L2 / L3 / L4 */
    private String classificationLv;

    private Long uploaderId;
    private OffsetDateTime uploadTime;

    /** pending / processing / ready / failed */
    private String hlsStatus;
    private String hlsPath;

    /** pending / processing / ready / failed */
    private String fingerprintStatus;
    private String fingerprintPath;

    public static VideoResponse from(VideoResource v) {
        if (v == null) return null;
        VideoResponse r = new VideoResponse();
        r.id = v.getId();
        r.title = v.getTitle();
        r.description = v.getDescription();
        r.fileHash = v.getFileHash();
        r.mimeType = v.getMimeType();
        r.durationSec = v.getDurationSec();
        r.sizeBytes = v.getSizeBytes();
        r.classificationLv = ResourceEnums.classificationLv(v.getClassificationLv());
        r.uploaderId = v.getUploaderId();
        r.uploadTime = v.getUploadTime();
        r.hlsStatus = ResourceEnums.hlsStatus(v.getHlsStatus());
        r.hlsPath = v.getHlsPath();
        r.fingerprintStatus = ResourceEnums.processStatus(v.getFingerprintStatus());
        r.fingerprintPath = v.getFingerprintPath();
        return r;
    }
}
