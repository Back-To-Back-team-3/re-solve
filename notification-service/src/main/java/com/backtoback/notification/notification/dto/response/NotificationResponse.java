package com.backtoback.notification.notification.dto.response;

import java.time.LocalDateTime;

import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;

/**
 * 알림 목록 조회의 개별 알림 항목 응답 (API 명세서 7.1.2).
 */
public record NotificationResponse(
    String notificationId,
    NotificationType type,
    String title,
    String content,
    String linkUrl,
    boolean isRead,
    LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
            String.valueOf(notification.getId()),
            notification.getType(),
            notification.getTitle(),
            notification.getContent(),
            notification.getLinkUrl(),
            notification.isRead(),
            notification.getCreatedAt()
        );
    }
}
