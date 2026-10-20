package com.backtoback.notification.delivery.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.notification.delivery.dto.NotificationEventResponse;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 알림 SSE 스트림 연결, 하트비트 전송, 미수신 알림 재전송 및 세션 종료를 담당하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    public static final long SSE_TIMEOUT_MILLIS = 60_000L;
    private static final String EVENT_NOTIFICATION = "notification";
    private static final String EVENT_HEARTBEAT = "heartbeat";

    private final EmitterRepository emitterRepository;
    private final NotificationRepository notificationRepository;

    /**
     * 클라이언트의 SSE 구독을 처리하고 SseEmitter를 반환한다.
     * Last-Event-ID 헤더가 존재하면 최근 24시간 내 미수신 알림을 재전송한다.
     *
     * @param memberId 회원 ID
     * @param lastEventId 마지막으로 수신한 이벤트 ID (nullable)
     * @return 등록된 SseEmitter 객체
     */
    @Transactional(readOnly = true)
    public SseEmitter subscribe(Long memberId, String lastEventId) {
        String emitterId = generateEmitterId(memberId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        emitter.onCompletion(() -> emitterRepository.deleteById(emitterId));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitterRepository.deleteById(emitterId);
        });
        emitter.onError(throwable -> emitterRepository.deleteById(emitterId));

        emitterRepository.save(emitterId, memberId, emitter);

        boolean replayed = replayMissedNotifications(emitter, memberId, lastEventId);
        if (!replayed) {
            sendHeartbeat(emitterId, emitter);
        }

        return emitter;
    }

    /**
     * 특정 회원에게 새 알림을 실시간으로 전송한다.
     *
     * @param memberId 수신 회원 ID
     * @param notification 전송할 알림 엔티티
     */
    public void sendToMember(Long memberId, Notification notification) {
        List<SseEmitter> emitters = emitterRepository.findAllByMemberId(memberId);
        if (emitters.isEmpty()) {
            return;
        }

        NotificationEventResponse response = NotificationEventResponse.from(notification);
        for (SseEmitter emitter : emitters) {
            try {
                emitter
                    .send(
                        SseEmitter
                            .event()
                            .id(String.valueOf(notification.getId()))
                            .name(EVENT_NOTIFICATION)
                            .data(response)
                    );
            } catch (IOException | IllegalStateException exception) {
                log.debug("알림 SSE 전송 실패, 이미터 정리 대상: memberId={}", memberId, exception);
            }
        }
    }

    /**
     * 연결된 모든 Emitter에 30초 주기 Heartbeat 이벤트를 전송한다.
     */
    public void broadcastHeartbeat() {
        Map<String, SseEmitter> allEmitters = emitterRepository.findAll();
        allEmitters.forEach(this::sendHeartbeat);
    }

    /**
     * 연결 유지 기준 시간(기본 60초)을 초과한 Emitter를 정상 완료(complete) 처리하여 클라이언트의 재연결을 유도한다.
     *
     * @param expirationDurationMillis 연결 유지 제한 시간(밀리초)
     */
    public void closeExpiredConnections(long expirationDurationMillis) {
        long now = System.currentTimeMillis();
        Map<String, SseEmitter> allEmitters = emitterRepository.findAll();

        allEmitters.forEach((emitterId, emitter) -> {
            Long creationTime = emitterRepository.getCreationTime(emitterId);
            if (creationTime != null && (now - creationTime) >= expirationDurationMillis) {
                try {
                    emitter.complete();
                } catch (Exception exception) {
                    log.debug("Emitter 완료 처리 중 예외 발생: emitterId={}", emitterId, exception);
                } finally {
                    emitterRepository.deleteById(emitterId);
                }
            }
        });
    }

    private boolean replayMissedNotifications(SseEmitter emitter, Long memberId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return false;
        }

        Long parsedLastEventId = parseLastEventId(lastEventId);
        if (parsedLastEventId == null) {
            return false;
        }

        LocalDateTime after = LocalDateTime.now().minusHours(24);
        List<Notification> missedNotifications
            = notificationRepository
                .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                    memberId,
                    parsedLastEventId,
                    after
                );

        if (missedNotifications.isEmpty()) {
            return false;
        }

        for (Notification notification : missedNotifications) {
            try {
                emitter
                    .send(
                        SseEmitter
                            .event()
                            .id(String.valueOf(notification.getId()))
                            .name(EVENT_NOTIFICATION)
                            .data(NotificationEventResponse.from(notification))
                    );
            } catch (IOException | IllegalStateException exception) {
                log.warn("미수신 알림 재전송 실패: memberId={}, lastEventId={}", memberId, lastEventId, exception);
                return true;
            }
        }

        return true;
    }

    private void sendHeartbeat(String emitterId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_HEARTBEAT).data(Collections.emptyMap()));
        } catch (IOException | IllegalStateException exception) {
            log.debug("하트비트 전송 실패, 이미터 정리: emitterId={}", emitterId, exception);
            emitterRepository.deleteById(emitterId);
        }
    }

    private Long parseLastEventId(String lastEventId) {
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException exception) {
            log.debug("유효하지 않은 Last-Event-ID 헤더 형식: {}", lastEventId);
            return null;
        }
    }

    private String generateEmitterId(Long memberId) {
        return memberId + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
