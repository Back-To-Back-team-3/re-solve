package com.backtoback.notification.delivery.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 실시간 알림 스트림(SSE)으로 전달하는 알림 데이터 DTO (API 명세서 7.1.1).
 */
public record NotificationEventResponse(
    String notificationId,
    NotificationType type,
    String title,
    String content,
    String linkUrl,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime createdAt
) {

    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    public static NotificationEventResponse from(Notification notification) {
        OffsetDateTime offsetDateTime
            = notification.getCreatedAt() != null ? notification.getCreatedAt().atZone(KST_ZONE).toOffsetDateTime()
                : OffsetDateTime.now(KST_ZONE);

        return new NotificationEventResponse(
            String.valueOf(notification.getId()),
            notification.getType(),
            notification.getTitle(),
            notification.getContent(),
            notification.getLinkUrl(),
            offsetDateTime
        );
    }
}
