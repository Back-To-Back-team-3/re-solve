package com.backtoback.notification.notification.dto.response;

/**
 * 7.1.5 알림 전체 읽음 처리 응답 DTO (API 명세서 7.1.5).
 *
 * @param updatedCount 읽음 처리된 알림 건수
 * @param unreadCount 잔여 미읽음 알림 개수 (전체 읽음 처리 후이므로 0)
 */
public record NotificationReadAllResponse(long updatedCount, long unreadCount) {
}
