package com.backtoback.notification.admin.dto.response;

import java.time.LocalDateTime;

import com.backtoback.notification.notification.domain.NotificationType;

public record AdminNotificationBroadcastResponse(
    NotificationType notificationType,
    String title,
    LocalDateTime broadcastedAt,
    int deliveredSseCount
) {
}
