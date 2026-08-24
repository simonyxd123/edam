package com.example.edam.websocket;

import com.example.edam.model.LeakDetection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * 实时通知 WebSocket Controller（v3.3 W-6.4）
 *
 * 通过 STOMP 将泄露告警等关键事件实时推送给在线管理员。
 * 客户端订阅 /topic/notifications 即可接收。
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class NotificationController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 广播泄露检测告警
     */
    public void broadcastLeakAlert(LeakDetection detection) {
        try {
            messagingTemplate.convertAndSend("/topic/notifications/leak", detection);
            log.info("leak_alert_broadcast detection_id={}", detection.getDetectionId());
        } catch (Exception e) {
            log.error("leak_alert_broadcast_failed detection_id={} error={}",
                detection.getDetectionId(), e.getMessage());
        }
    }
}