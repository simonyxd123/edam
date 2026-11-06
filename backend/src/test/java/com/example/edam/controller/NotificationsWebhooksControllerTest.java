package com.example.edam.controller;

import com.example.edam.model.Notification;
import com.example.edam.model.NotificationPreference;
import com.example.edam.model.Webhook;
import com.example.edam.repository.NotificationPreferenceRepository;
import com.example.edam.repository.NotificationRepository;
import com.example.edam.repository.WebhookDeliveryRepository;
import com.example.edam.repository.WebhookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知 / 回调端点的单测。
 *
 * <p>此前这些端点全是写死的假返回：
 * <ul>
 *   <li>{@code POST /webhooks} 造一个假 secret 和假 ID 返回 201，**什么都不落库**</li>
 *   <li>{@code DELETE /webhooks/{id}} 只打日志就返回 204</li>
 *   <li>{@code PUT /notifications/preferences} <b>原样回显请求体</b> ——
 *       用户以为设置保存了，其实什么都没存</li>
 *   <li>通知列表恒为 {@code items: []}、{@code unread_count: 0}</li>
 * </ul>
 */
class NotificationsWebhooksControllerTest {

    private static final Long OWNER = 10L;
    private static final Long STRANGER = 20L;

    private WebhookRepository webhookRepository;
    private WebhookDeliveryRepository webhookDeliveryRepository;
    private NotificationRepository notificationRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private WebhooksController webhooksController;
    private NotificationsController notificationsController;

    @BeforeEach
    void setUp() {
        webhookRepository = mock(WebhookRepository.class);
        webhookDeliveryRepository = mock(WebhookDeliveryRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        webhooksController = new WebhooksController(
            webhookRepository, webhookDeliveryRepository);
        notificationsController = new NotificationsController(
            notificationRepository, preferenceRepository, new ObjectMapper());
    }

    // ==================================================================
    // 回调注册
    // ==================================================================

    @Test
    @DisplayName("注册回调要真落库，secret 明文只返回一次")
    void createWebhookPersists() {
        WebhooksController.CreateWebhookRequest request = new WebhooksController.CreateWebhookRequest();
        request.setUrl("https://example.com/hook");
        request.setEvents(List.of("video.ready", "doc.approved"));

        webhooksController.create(request, OWNER);

        verify(webhookRepository).insert(any(Webhook.class));
        org.mockito.ArgumentCaptor<Webhook> captor =
            org.mockito.ArgumentCaptor.forClass(Webhook.class);
        verify(webhookRepository).insert(captor.capture());
        Webhook saved = captor.getValue();
        assertEquals(OWNER, saved.getOwnerId());
        assertEquals("https://example.com/hook", saved.getUrl());
        assertEquals("video.ready,doc.approved", saved.getEvents());
        assertEquals(1, saved.getStatus());
        // 库里存的是**哈希**，不是明文
        assertNotNull(saved.getSecretHash());
        assertEquals(64, saved.getSecretHash().length());
        assertTrue(!saved.getSecretHash().contains("whsec_"));
    }

    @Test
    @DisplayName("回调地址指向内网 / 回环 → 拒绝（SSRF）")
    void internalUrlRejected() {
        for (String url : List.of(
                "http://127.0.0.1/hook",
                "http://localhost:8080/hook",
                "http://10.1.2.3/hook",
                "http://192.168.1.1/hook",
                "http://169.254.169.254/latest/meta-data/",
                "http://172.16.0.1/hook")) {
            WebhooksController.CreateWebhookRequest request =
                new WebhooksController.CreateWebhookRequest();
            request.setUrl(url);
            request.setEvents(List.of("video.ready"));
            assertThrows(IllegalArgumentException.class,
                () -> webhooksController.create(request, OWNER), "应拒绝: " + url);
        }
        verify(webhookRepository, never()).insert(any(Webhook.class));
    }

    @Test
    @DisplayName("非 http(s) 协议 → 拒绝")
    void nonHttpSchemeRejected() {
        WebhooksController.CreateWebhookRequest request =
            new WebhooksController.CreateWebhookRequest();
        request.setUrl("file:///etc/passwd");
        request.setEvents(List.of("video.ready"));
        assertThrows(IllegalArgumentException.class,
            () -> webhooksController.create(request, OWNER));
    }

    @Test
    @DisplayName("注销别人的回调 → 404，不动数据")
    void deleteOthersWebhookRejected() {
        when(webhookRepository.findByIdAndOwner(7L, STRANGER)).thenReturn(null);

        assertThrows(ResponseStatusException.class,
            () -> webhooksController.delete(7L, STRANGER));
        verify(webhookRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("注销自己的回调 → 真删")
    void deleteOwnWebhook() {
        Webhook webhook = new Webhook();
        webhook.setId(7L);
        webhook.setOwnerId(OWNER);
        when(webhookRepository.findByIdAndOwner(7L, OWNER)).thenReturn(webhook);

        webhooksController.delete(7L, OWNER);

        verify(webhookRepository).deleteById(7L);
    }

    @Test
    @DisplayName("读别人的投递历史 → 404")
    void deliveriesOfOthersWebhookRejected() {
        when(webhookRepository.findByIdAndOwner(7L, STRANGER)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> webhooksController.deliveries(7L, STRANGER, 1));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("回调列表按 owner 查")
    void listIsScopedToOwner() {
        when(webhookRepository.findByOwner(OWNER)).thenReturn(List.of());

        webhooksController.list(OWNER);

        verify(webhookRepository).findByOwner(OWNER);
    }

    // ==================================================================
    // 通知
    // ==================================================================

    @Test
    @DisplayName("标记已读：不是自己的通知 → 404")
    void markReadOthersNotification() {
        when(notificationRepository.markRead(5L, STRANGER)).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> notificationsController.markRead(5L, STRANGER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("标记已读：自己的通知 → 204")
    void markReadOwnNotification() {
        when(notificationRepository.markRead(5L, OWNER)).thenReturn(1);

        notificationsController.markRead(5L, OWNER);

        verify(notificationRepository).markRead(5L, OWNER);
    }

    @Test
    @DisplayName("全部已读：返回真实的影响行数")
    void markAllReadReturnsRealCount() {
        when(notificationRepository.markAllRead(OWNER)).thenReturn(3);

        Map<String, Object> resp = notificationsController.markAllRead(OWNER);

        assertEquals(3, resp.get("updated_count"));
    }

    @Test
    @DisplayName("更新偏好要真落库（此前是原样回显）")
    void updatePreferencesPersists() {
        when(preferenceRepository.findByUserId(OWNER)).thenReturn(null);

        Map<String, Object> body = Map.of(
            "channels", Map.of("email", false, "sms", true, "im_webhook", true, "in_app", true),
            "types", Map.of("approval", true, "key_alert", false),
            "quiet_hours", Map.of("enabled", true, "start", "23:00:00", "end", "07:30:00")
        );

        Map<String, Object> resp = notificationsController.updatePreferences(body, OWNER);

        verify(preferenceRepository).insert(any(NotificationPreference.class));
        org.mockito.ArgumentCaptor<NotificationPreference> captor =
            org.mockito.ArgumentCaptor.forClass(NotificationPreference.class);
        verify(preferenceRepository).insert(captor.capture());
        NotificationPreference saved = captor.getValue();
        assertEquals(OWNER, saved.getUserId());
        assertTrue(saved.getChannels().contains("\"sms\":true"));
        assertTrue(saved.getTypes().contains("\"key_alert\":false"));
        assertEquals(1, saved.getQuietHoursEnabled());
        assertEquals("23:00:00", saved.getQuietHoursStart());

        // 回读的是**落库后的值**，不是原样回显
        assertNotNull(resp.get("channels"));
        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) resp.get("channels");
        assertEquals(true, channels.get("sms"));
    }

    @Test
    @DisplayName("更新已存在的偏好 → update 而不是 insert")
    void updateExistingPreferences() {
        NotificationPreference existing = new NotificationPreference();
        existing.setId(3L);
        existing.setUserId(OWNER);
        existing.setChannels("{\"email\":true}");
        existing.setTypes("{\"approval\":true}");
        existing.setQuietHoursEnabled(0);
        when(preferenceRepository.findByUserId(OWNER)).thenReturn(existing);

        notificationsController.updatePreferences(
            Map.of("channels", Map.of("email", false)), OWNER);

        verify(preferenceRepository, never()).insert(any(NotificationPreference.class));
        verify(preferenceRepository).updateById(existing);
    }

    @Test
    @DisplayName("没有偏好记录时返回默认偏好")
    void defaultPreferencesWhenNoneStored() {
        when(preferenceRepository.findByUserId(OWNER)).thenReturn(null);

        Map<String, Object> resp = notificationsController.getPreferences(OWNER);

        assertNotNull(resp.get("channels"));
        assertNotNull(resp.get("quiet_hours"));
    }
}
