package com.example.edam.controller;

import com.example.edam.repository.DistributionApprovalRepository;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.VideoResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仪表板统计 Controller（v3.2 V-1）
 *
 * GET /dashboard/stats → { total_videos, total_documents, pending_approvals, recent_logins }
 * 数字从 sys_user / video_resource / doc_resource / distribution_approval / operation_log 实时聚合
 * 权限 dashboard:read 即可访问
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final VideoResourceRepository videoRepository;
    private final DocResourceRepository docRepository;
    private final DistributionApprovalRepository distributionRepository;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_videos", videoRepository.countActive());
        result.put("total_documents", docRepository.countActive());
        result.put("pending_approvals", distributionRepository.countPending());

        // 今日登录：取当天 0 点（上海时区）作为截止
        LocalDateTime startOfDay = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .toLocalDate().atStartOfDay();
        result.put("recent_logins", distributionRepository.countLoginsSince(startOfDay));

        return result;
    }
}
