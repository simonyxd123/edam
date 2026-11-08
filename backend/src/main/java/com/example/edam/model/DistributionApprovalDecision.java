package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外发审批的**决策流水**（distribution_approval_decision 表）。
 *
 * <p><b>为什么需要这张表</b>（2026-11-07 补）：
 * 原先 {@code DistributionController.decide()} 只把
 * {@code distribution_approval.status} 改成 approved/rejected，
 * {@code comment}（审批意见）收了**却从不保存**，
 * 而 {@code distribution_approval_decision} 这张表在 Java 侧**零引用** ——
 * 也就是说：<b>谁批的、什么时候批的、批语是什么，全都查不到</b>。
 *
 * <p>对防泄密系统这是合规硬伤 —— 外发审批是"把资产送到公司外面"这道闸，
 * 闸门的每一次开合都必须留痕。审批单主表只保留**当前状态**，
 * 决策流水保留**历史**，两者合起来才是完整的审计证据链。
 *
 * <p>字段与表结构对齐（见 {@code V20260812_1000__init_schema.sql} 3.4）。
 */
@Data
@TableName("distribution_approval_decision")
public class DistributionApprovalDecision {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应的外发审批单 id */
    private Long approvalId;

    /** 审批人 user_id */
    private Long approverId;

    /** 1=approve 2=reject */
    private Integer decision;

    /** 审批意见（可空，但强烈建议填 —— 驳回时必填） */
    private String comment;

    private LocalDateTime decidedAt;
}
