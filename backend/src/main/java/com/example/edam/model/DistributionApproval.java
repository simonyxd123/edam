package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 外发审批实体
 * 对应表：distribution_approval（参考 database_schema.md 3.3）
 */
@Data
@TableName("distribution_approval")
public class DistributionApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;
    private Long applicantId;
    private String externalRecipientName;
    private String externalRecipientEmail;
    private String externalRecipientOrg;
    private String reason;
    private Integer validHours;
    private Integer maxOpenCount;
    private Boolean allowForward;
    private Boolean allowPrint;

    /** 0=pending 1=approved 2=rejected 3=expired 4=revoked */
    private Integer status;
    private Integer currentOpenCount;
    private OffsetDateTime finalDecisionAt;
    private Long revokedBy;
    private OffsetDateTime revokedAt;
    private String revokeReason;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @Version
    private Integer version;
}