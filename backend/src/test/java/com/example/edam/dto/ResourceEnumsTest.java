package com.example.edam.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ResourceEnums} 单测。
 *
 * <p>这些映射以前是 3 个 controller 里各抄一份的 {@code statusToString}，
 * 收敛到这里后如果映射写错，前端的标签会静默显示错的状态。
 */
class ResourceEnumsTest {

    @Test
    @DisplayName("密级映射")
    void classificationLv() {
        assertEquals("L1", ResourceEnums.classificationLv(1));
        assertEquals("L2", ResourceEnums.classificationLv(2));
        assertEquals("L3", ResourceEnums.classificationLv(3));
        assertEquals("L4", ResourceEnums.classificationLv(4));
        assertEquals("L1", ResourceEnums.classificationLv(null), "null 应降级为 L1");
        assertEquals("L1", ResourceEnums.classificationLv(99), "非法值应降级为 L1");
    }

    @Test
    @DisplayName("HLS 状态映射")
    void hlsStatus() {
        assertEquals("pending", ResourceEnums.hlsStatus(0));
        assertEquals("processing", ResourceEnums.hlsStatus(1));
        assertEquals("ready", ResourceEnums.hlsStatus(2));
        assertEquals("failed", ResourceEnums.hlsStatus(3));
        assertEquals("pending", ResourceEnums.hlsStatus(null));
        assertEquals("unknown", ResourceEnums.hlsStatus(9));
    }

    @Test
    @DisplayName("水印 / 预览状态映射（含 skipped）")
    void processStatus() {
        assertEquals("pending", ResourceEnums.processStatus(0));
        assertEquals("processing", ResourceEnums.processStatus(1));
        assertEquals("ready", ResourceEnums.processStatus(2));
        assertEquals("failed", ResourceEnums.processStatus(3));
        assertEquals("skipped", ResourceEnums.processStatus(4));
        assertEquals("pending", ResourceEnums.processStatus(null));
    }

    @Test
    @DisplayName("外发审批状态映射（语义与前两者不同）")
    void approvalStatus() {
        assertEquals("pending", ResourceEnums.approvalStatus(0));
        assertEquals("approved", ResourceEnums.approvalStatus(1));
        assertEquals("rejected", ResourceEnums.approvalStatus(2));
        assertEquals("expired", ResourceEnums.approvalStatus(3));
        assertEquals("revoked", ResourceEnums.approvalStatus(4));
        assertEquals("pending", ResourceEnums.approvalStatus(null));
        assertEquals("unknown", ResourceEnums.approvalStatus(9));
    }
}
