package com.example.edam.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edam.model.DistributionApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface DistributionApprovalRepository extends BaseMapper<DistributionApproval> {

    /**
     * 待审批的 distribution 数量（status=0 是 pending）
     */
    @Select("SELECT COUNT(*) FROM distribution_approval WHERE status = 0")
    long countPending();

    /**
     * 指定时间之后的 login 审计日志条数（操作 operation_log 表）
     */
    @Select("SELECT COUNT(*) FROM operation_log WHERE operation_type = 'login' AND timestamp >= #{since}")
    long countLoginsSince(LocalDateTime since);
}