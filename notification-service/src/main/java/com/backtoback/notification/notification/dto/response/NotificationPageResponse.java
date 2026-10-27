package com.backtoback.notification.notification.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

import com.backtoback.notification.notification.domain.Notification;

/**
 * 알림 목록 페이징 및 미읽음 개수 응답 (API 명세서 7.1.2).
 */
public record NotificationPageResponse(
    long unreadCount,
    List<NotificationResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {

    public static NotificationPageResponse of(Page<Notification> page, long unreadCount) {
        List<NotificationResponse> content = page.getContent().stream().map(NotificationResponse::from).toList();

        return new NotificationPageResponse(
            unreadCount,
            content,
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.hasNext()
        );
    }
}
