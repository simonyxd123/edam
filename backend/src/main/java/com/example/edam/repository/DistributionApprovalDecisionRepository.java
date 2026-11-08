package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.DistributionApprovalDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DistributionApprovalDecisionRepository
    extends BaseMapper<DistributionApprovalDecision> {

    /**
     * 某个审批单的完整决策流水（按时间正序）。
     *
     * <p>审批单主表只存**当前**状态；这张流水表存**历史** ——
     * 谁批的、什么时候批的、批语是什么。
     */
    @Select("SELECT * FROM distribution_approval_decision "
        + "WHERE approval_id = #{approvalId} ORDER BY decided_at ASC, id ASC")
    List<DistributionApprovalDecision> findByApprovalId(Long approvalId);
}
