package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 泄露检测记录（v3.3 W-6.3）
 */
@Data
@TableName("leak_detection")
public class LeakDetection {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 检测任务 ID（UUID）*/
    private String detectionId;

    /** 资源类型 */
    private String resourceType;  // video / document

    /** 资源 ID（EDAM 系统内）*/
    private Long resourceId;

    /** 疑似泄露文件路径 */
    private String leakedFilePath;

    /** 泄露文件 SHA-256 */
    private String leakedFileHash;

    /** 检测方法：phash / dct / manual */
    private String detectionMethod;

    /** 匹配用户 ID */
    private Long matchedUserId;

    /** 匹配会话 ID */
    private String matchedSessionId;

    /** 匹配置信度（0-100）*/
    private Double matchScore;

    /** 匹配帧数 */
    private Integer matchedFrames;

    /** 总查询帧数 */
    private Integer totalFrames;

    /** 状态：pending / confirmed / dismissed */
    private String status;

    private LocalDateTime detectedAt;

    private LocalDateTime reviewedAt;

    private Long reviewedBy;

    private String reviewNote;
}