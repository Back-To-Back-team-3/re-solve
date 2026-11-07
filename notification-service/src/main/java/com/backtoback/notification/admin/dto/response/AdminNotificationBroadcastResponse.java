package com.backtoback.notification.admin.dto.response;

import java.time.OffsetDateTime;

import com.backtoback.notification.notification.domain.NotificationType;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 관리자 시스템 전역 공지 알림 발송 응답 DTO (API 명세서 7.2.1).
 */
public record AdminNotificationBroadcastResponse(
    NotificationType notificationType,
    String title,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime broadcastedAt,
    int deliveredSseCount
) {

    public static AdminNotificationBroadcastResponse of(
        NotificationType notificationType,
        String title,
        OffsetDateTime broadcastedAt,
        int deliveredSseCount
    ) {
        return new AdminNotificationBroadcastResponse(notificationType, title, broadcastedAt, deliveredSseCount);
    }
}
