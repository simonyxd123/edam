package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据分类变更审计（v3.3 W-7.4）
 *
 * 记录密级变更历史（含变更原因、变更人）
 */
@Data
@TableName("data_classification_audit")
public class DataClassificationAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 资源类型（video / document）*/
    private String resourceType;

    /** 资源 ID */
    private Long resourceId;

    /** 旧密级 */
    private String oldClassification;

    /** 新密级 */
    private String newClassification;

    /** 变更原因 */
    private String changeReason;

    /** 变更人 */
    private Long changedBy;

    /** 变更方式（auto / manual）*/
    private String changeMethod;

    /** 匹配的规则 ID（自动分类时）*/
    private Long ruleId;

    private LocalDateTime changedAt;
}