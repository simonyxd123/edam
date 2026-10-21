package com.example.edam.dto;

import com.example.edam.model.DocResource;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文档资源对外 DTO。
 *
 * <p>状态 / 密级字段已从 Integer 映射为字符串（见 {@link ResourceEnums}）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DocResponse {

    private Long id;
    private String title;
    private String fileType;
    private String fileHash;
    private String mimeType;
    private Long sizeBytes;

    /** L1 / L2 / L3 / L4 */
    private String classificationLv;

    private Long uploaderId;
    private OffsetDateTime uploadTime;

    /** pending / processing / ready / failed / skipped */
    private String watermarkStatus;

    /** pending / processing / ready / failed */
    private String previewStatus;

    private Integer encrypted;
    private Long viewCount;

    public static DocResponse from(DocResource d) {
        if (d == null) return null;
        DocResponse r = new DocResponse();
        r.id = d.getId();
        r.title = d.getTitle();
        r.fileType = d.getFileType();
        r.fileHash = d.getFileHash();
        r.mimeType = d.getMimeType();
        r.sizeBytes = d.getSizeBytes();
        r.classificationLv = ResourceEnums.classificationLv(d.getClassificationLv());
        r.uploaderId = d.getUploaderId();
        r.uploadTime = d.getUploadTime();
        r.watermarkStatus = ResourceEnums.processStatus(d.getWatermarkStatus());
        r.previewStatus = ResourceEnums.processStatus(d.getPreviewStatus());
        r.encrypted = d.getEncrypted();
        r.viewCount = d.getViewCount();
        return r;
    }
}
