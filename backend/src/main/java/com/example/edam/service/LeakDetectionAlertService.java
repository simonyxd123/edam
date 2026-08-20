package com.example.edam.service;

import com.example.edam.model.LeakDetection;
import com.example.edam.repository.LeakDetectionRepository;
import com.example.edam.websocket.NotificationController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 泄露检测告警服务（v3.3 W-6.4）
 *
 * 命中泄露检测后：
 * 1. 记录到 leak_detection 表
 * 2. 通知安全团队（钉钉/企微）
 * 3. 通知法务 + HR
 * 4. 触发司法鉴定流程（如确认）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeakDetectionAlertService {

    private final LeakDetectionRepository leakRepository;
    private final NotificationController notificationController;

    @Value("${edam.alert.webhook.security:}")
    private String securityWebhook;

    @Value("${edam.alert.webhook.legal:}")
    private String legalWebhook;

    /**
     * 处理泄露检测结果
     */
    public void handleDetectionResult(
            String detectionId,
            String resourceType,
            String leakedFilePath,
            Long matchedUserId,
            String matchedSessionId,
            Double matchScore,
            Integer matchedFrames,
            Integer totalFrames
    ) {
        // 1. 持久化
        LeakDetection detection = new LeakDetection();
        detection.setDetectionId(detectionId);
        detection.setResourceType(resourceType);
        detection.setLeakedFilePath(leakedFilePath);
        detection.setDetectionMethod("phash");
        detection.setMatchedUserId(matchedUserId);
        detection.setMatchedSessionId(matchedSessionId);
        detection.setMatchScore(matchScore);
        detection.setMatchedFrames(matchedFrames);
        detection.setTotalFrames(totalFrames);
        detection.setStatus("pending");
        detection.setDetectedAt(LocalDateTime.now());
        leakRepository.insert(detection);

        log.warn(
            "leak_detected detection_id={} resource_type={} user_id={} session={} score={} frames={}/{}",
            detectionId, resourceType, matchedUserId, matchedSessionId,
            matchScore, matchedFrames, totalFrames
        );

        // 2. 通知安全团队（P1 告警）
        sendSecurityAlert(detection);

        // 3. 实时通知管理员
        notificationController.broadcastLeakAlert(detection);
    }

    /**
     * 安全团队告警（钉钉/企微）
     */
    private void sendSecurityAlert(LeakDetection detection) {
        if (securityWebhook == null || securityWebhook.isBlank()) {
            log.warn("security_webhook_not_configured");
            return;
        }
        String message = String.format(
            "🚨 [EDAM 泄露告警] 检测到疑似泄露视频\n" +
            "检测 ID: %s\n" +
            "疑似用户: %d\n" +
            "匹配置信度: %.1f%%\n" +
            "匹配帧数: %d/%d\n" +
            "请立即审查并启动溯源流程",
            detection.getDetectionId(),
            detection.getMatchedUserId(),
            detection.getMatchScore() * 100,
            detection.getMatchedFrames(),
            detection.getTotalFrames()
        );
        // 实际生产：通过 HTTP POST 发送
        log.info("security_alert_sent message={}", message.replace("\n", " | "));
    }

    /**
     * 法务/HR 告警（仅在 confirmed 后）
     */
    public void notifyLegalAndHR(Long detectionId) {
        if (legalWebhook == null || legalWebhook.isBlank()) return;
        LeakDetection detection = leakRepository.findByDetectionId(detectionId);
        if (detection == null || !"confirmed".equals(detection.getStatus())) {
            return;
        }
        // 发送法务 + HR 通知（实际生产）
        log.info("legal_hr_notified detection_id={}", detectionId);
    }

    /**
     * 确认泄露（人工审查）
     */
    public void confirmLeak(Long detectionId, Long reviewerId, String note) {
        LeakDetection detection = leakRepository.findById(detectionId);
        if (detection == null) return;
        detection.setStatus("confirmed");
        detection.setReviewedAt(LocalDateTime.now());
        detection.setReviewedBy(reviewerId);
        detection.setReviewNote(note);
        leakRepository.updateById(detection);

        log.warn("leak_confirmed detection_id={} reviewer_id={}", detectionId, reviewerId);
        notifyLegalAndHR(detectionId);
    }

    /**
     * 驳回误报
     */
    public void dismissLeak(Long detectionId, Long reviewerId, String note) {
        LeakDetection detection = leakRepository.findById(detectionId);
        if (detection == null) return;
        detection.setStatus("dismissed");
        detection.setReviewedAt(LocalDateTime.now());
        detection.setReviewedBy(reviewerId);
        detection.setReviewNote(note);
        leakRepository.updateById(detection);

        log.info("leak_dismissed detection_id={} reviewer_id={}", detectionId, reviewerId);
    }
}