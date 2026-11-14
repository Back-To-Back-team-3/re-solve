package com.backtoback.notification.event.dto;

import com.backtoback.notification.notification.domain.Notification;

/**
 * 알림 영속화 트랜잭션 커밋 이후 SSE 스트림 전송을 트리거하는 내부 이벤트 (이벤트 계약서 5.1).
 */
public record NotificationCreatedEvent(Long memberId, Notification notification) {
}
