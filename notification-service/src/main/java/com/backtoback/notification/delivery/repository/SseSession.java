package com.backtoback.notification.delivery.repository;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 단일 SSE 연결의 식별자, 소유 회원, 이미터, 생성 시각 및 마지막 전송 알림 ID 워터마크를 하나로 묶은 세션 객체.
 * 저장소 엔트리와 전송 상태 추적을 단일 객체로 응집하여 연결 종료·실패 시 상태 누수를 방지한다.
 *
 * @param emitterId Emitter 고유 식별자
 * @param memberId 회원 ID
 * @param emitter SSE 이미터 객체
 * @param creationTime 연결 생성 시각 (epoch millis, Clock 기준)
 * @param lastSentNotificationId 해당 세션으로 전송 완료된 최대 알림 ID 워터마크
 */
public record SseSession(
    String emitterId,
    Long memberId,
    SseEmitter emitter,
    long creationTime,
    AtomicLong lastSentNotificationId
) {

    /**
     * 초기 워터마크를 지정하여 새 SSE 세션 객체를 생성한다.
     *
     * @param emitterId Emitter 고유 식별자
     * @param memberId 회원 ID
     * @param emitter SSE 이미터 객체
     * @param creationTime 연결 생성 시각 (epoch millis, Clock 기준)
     * @param initialLastEventId 초기 마지막 이벤트 ID (없으면 0L)
     * @return 생성된 SseSession
     */
    public static SseSession create(
        String emitterId,
        Long memberId,
        SseEmitter emitter,
        long creationTime,
        long initialLastEventId
    ) {
        return new SseSession(emitterId, memberId, emitter, creationTime, new AtomicLong(initialLastEventId));
    }
}
