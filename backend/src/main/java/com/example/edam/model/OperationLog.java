package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 操作日志实体
 * 对应表：operation_log（按月分表 operation_log_YYYYMM）
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String operationType;
    private String resourceType;
    private Long resourceId;
    private String ip;
    private String userAgent;

    /** 1=success 2=failure 3=denied */
    private Integer result;
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime timestamp;
}