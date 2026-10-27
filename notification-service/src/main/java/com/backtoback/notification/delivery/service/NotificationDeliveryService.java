package com.backtoback.notification.delivery.service;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.notification.delivery.dto.NotificationEventResponse;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.delivery.repository.SseSession;
import com.backtoback.notification.global.error.NotificationException;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.notification.repository.NotificationWithdrawnMemberRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 알림 SSE 스트림 연결, 하트비트 전송, 미수신 알림 재전송 및 세션 종료를 담당하는 서비스.
 * 재전송과 실시간 발송 경합 시의 순서 역전 및 중복 발송을 방지하기 위해 세션 단위 동기화를 보장한다.
 * <p>
 * [워터마크 기반 중복 방지의 한계]
 * 세션별 마지막 전송 알림 ID(lastSentNotificationId) 워터마크와 Last-Event-ID 재전송은 자동 증가 PK(id) 순서를 기준으로 동작한다.
 * 다중 트랜잭션 환경에서 높은 ID(예: 103)가 낮은 ID(예: 102)보다 먼저 커밋되어 전송되면,
 * 뒤늦게 커밋된 102번은 {@code id <= tracker} 조건 및 {@code id > Last-Event-ID} 조건에 의해 실시간 스트림·재전송에서 건너뛰어질 수 있다.
 * 이는 전역 시퀀스 락 없이 경량 중복 제거를 수행하기 위한 의도된 트레이드오프이며,
 * 건너뛰어진 알림도 DB에는 미읽음(is_read = false)으로 저장되므로 알림 목록·미읽음 개수 조회 API를 통해 보완된다.
 * </p>
 */
@Slf4j
@Service
public class NotificationDeliveryService {

    public static final long SSE_TIMEOUT_MILLIS = 60_000L;
    private static final String EVENT_NOTIFICATION = "notification";
    private static final String EVENT_HEARTBEAT = "heartbeat";

    private final EmitterRepository emitterRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationWithdrawnMemberRepository withdrawnMemberRepository;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryService(
        EmitterRepository emitterRepository,
        NotificationRepository notificationRepository,
        NotificationWithdrawnMemberRepository withdrawnMemberRepository,
        Clock clock
    ) {
        this.emitterRepository = emitterRepository;
        this.notificationRepository = notificationRepository;
        this.withdrawnMemberRepository = withdrawnMemberRepository;
        this.clock = clock;
    }

    public NotificationDeliveryService(
        EmitterRepository emitterRepository,
        NotificationRepository notificationRepository,
        Clock clock
    ) {
        this(emitterRepository, notificationRepository, null, clock);
    }

    /**
     * 클라이언트의 SSE 구독을 처리하고 SseEmitter를 반환한다.
     * 이미 탈퇴 처리({@code MemberWithdrawn})된 회원이 유효 기간이 남은 Access Token으로 재연결을 시도하는 경우
     * {@code AUTH_ACCESS_DENIED}(403)를 던져 브라우저 {@code EventSource} 자동 재연결을 차단한다.
     * 느린 클라이언트로의 SSE 네트워크 전송 중 DB 커넥션을 점유하지 않도록 메서드 레벨 트랜잭션을 두지 않으며,
     * 미수신 알림 조회 시점에만 Repository 읽기 트랜잭션을 짧게 사용한다.
     *
     * @param memberId 회원 ID
     * @param lastEventId 마지막으로 수신한 이벤트 ID (nullable)
     * @return 등록된 SseEmitter 객체
     * @throws NotificationException 탈퇴한 회원의 구독 요청인 경우 ({@code AUTH_ACCESS_DENIED})
     */
    public SseEmitter subscribe(Long memberId, String lastEventId) {
        if (withdrawnMemberRepository != null && withdrawnMemberRepository.existsByMemberId(memberId)) {
            throw new NotificationException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        String emitterId = generateEmitterId(memberId);
        SseEmitter emitter = createEmitter();
        Long parsedLastEventId = parseLastEventId(lastEventId);
        long initialLastEventId = parsedLastEventId != null ? parsedLastEventId : 0L;

        SseSession session = SseSession.create(emitterId, memberId, emitter, clock.millis(), initialLastEventId);

        emitter.onCompletion(() -> emitterRepository.deleteById(emitterId));
        emitter.onTimeout(() -> cleanup(emitterId, emitter, null));
        emitter.onError(throwable -> emitterRepository.deleteById(emitterId));

        emitterRepository.save(session);

        // 재전송 중 실시간 발송이 끼어들어 순서가 역전되거나 중복 전송되는 것을 막기 위해 세션 모니터 락을 사용한다.
        synchronized (emitter) {
            boolean replayed = replayMissedNotifications(session, parsedLastEventId);
            if (!replayed) {
                sendHeartbeat(session);
            }
        }

        return emitter;
    }

    /**
     * 특정 회원에게 새 알림을 실시간으로 전송한다.
     * 재전송 중인 세션은 동기화 대기 후 순서대로 발송하며, 이미 재전송된 알림(id <= 워터마크)은 건너뛴다.
     * 전송 실패 시 세션을 저장소에서 즉시 제거하고 에러 종료 처리한다.
     *
     * @param memberId 수신 회원 ID
     * @param notification 전송할 알림 엔티티
     */
    public void sendToMember(Long memberId, Notification notification) {
        List<SseSession> sessions = emitterRepository.findAllByMemberId(memberId);
        if (sessions.isEmpty()) {
            return;
        }

        NotificationEventResponse response = NotificationEventResponse.from(notification);
        for (SseSession session : sessions) {
            SseEmitter emitter = session.emitter();
            synchronized (emitter) {
                AtomicLong tracker = session.lastSentNotificationId();
                if (notification.getId() != null && notification.getId() <= tracker.get()) {
                    continue;
                }

                try {
                    emitter
                        .send(
                            SseEmitter
                                .event()
                                .id(String.valueOf(notification.getId()))
                                .name(EVENT_NOTIFICATION)
                                .data(response)
                        );
                    if (notification.getId() != null) {
                        tracker.set(Math.max(tracker.get(), notification.getId()));
                    }
                } catch (IOException | IllegalStateException exception) {
                    log
                        .debug(
                            "알림 SSE 전송 실패, 세션 정리: emitterId={}, memberId={}",
                            session.emitterId(),
                            memberId,
                            exception
                        );
                    cleanup(session.emitterId(), emitter, exception);
                }
            }
        }
    }

    /**
     * 연결된 모든 활성 세션에 30초 주기 Heartbeat 이벤트를 전송한다.
     */
    public void broadcastHeartbeat() {
        List<SseSession> allSessions = emitterRepository.findAll();
        allSessions.forEach(this::sendHeartbeat);
    }

    /**
     * 탈퇴한 회원의 모든 활성 SSE 세션을 저장소에서 제거하고 정상 종료(complete) 처리한다 (이벤트 계약서 4.1 M3, 5.1).
     *
     * @param memberId 탈퇴한 회원 ID
     */
    public void disconnectMember(Long memberId) {
        List<SseSession> sessions = emitterRepository.findAllByMemberId(memberId);
        for (SseSession session : sessions) {
            SseEmitter emitter = session.emitter();
            synchronized (emitter) {
                cleanup(session.emitterId(), emitter, null);
            }
        }
    }

    /**
     * 연결 유지 기준 시간(기본 60초)을 초과한 세션을 정상 완료(complete) 처리하여 클라이언트의 재연결을 유도한다.
     * <p>
     * 일반적인 서블릿 컨테이너 환경에서는 SseEmitter 자체 타임아웃(60초)이 먼저 동작하며,
     * 본 메서드는 컨테이너 비동기 타임아웃 콜백이 지연·누락되거나 동작하지 않는 환경에서
     * 인메모리 세션 누수를 확정적으로 방지하는 보조 스위퍼(Sweeper) 역할을 수행한다.
     * </p>
     *
     * @param expirationDurationMillis 연결 유지 제한 시간(밀리초)
     */
    public void closeExpiredConnections(long expirationDurationMillis) {
        long now = clock.millis();
        List<SseSession> allSessions = emitterRepository.findAll();

        for (SseSession session : allSessions) {
            if ((now - session.creationTime()) >= expirationDurationMillis) {
                SseEmitter emitter = session.emitter();
                synchronized (emitter) {
                    cleanup(session.emitterId(), emitter, null);
                }
            }
        }
    }

    /**
     * SseEmitter 인스턴스를 생성한다 (단위 테스트에서 서브클래스 주입을 위해 패키지 전용으로 열어둠).
     *
     * @return 새 SseEmitter 인스턴스
     */
    SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MILLIS);
    }

    private boolean replayMissedNotifications(SseSession session, Long parsedLastEventId) {
        if (parsedLastEventId == null) {
            return false;
        }

        List<Notification> missedNotifications = findMissedNotifications(session.memberId(), parsedLastEventId);
        if (missedNotifications.isEmpty()) {
            return false;
        }

        SseEmitter emitter = session.emitter();
        AtomicLong tracker = session.lastSentNotificationId();
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
                if (notification.getId() != null) {
                    tracker.set(Math.max(tracker.get(), notification.getId()));
                }
            } catch (IOException | IllegalStateException exception) {
                log
                    .warn(
                        "미수신 알림 재전송 실패, 세션 정리: emitterId={}, memberId={}, lastEventId={}",
                        session.emitterId(),
                        session.memberId(),
                        parsedLastEventId,
                        exception
                    );
                cleanup(session.emitterId(), emitter, exception);
                return true;
            }
        }

        return true;
    }

    private List<Notification> findMissedNotifications(Long memberId, Long parsedLastEventId) {
        LocalDateTime after = LocalDateTime.now(clock).minusHours(24);
        return notificationRepository
            .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                memberId,
                parsedLastEventId,
                after
            );
    }

    private void sendHeartbeat(SseSession session) {
        SseEmitter emitter = session.emitter();
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_HEARTBEAT).data(Collections.emptyMap()));
            } catch (IOException | IllegalStateException exception) {
                log.debug("하트비트 전송 실패, 세션 정리: emitterId={}", session.emitterId(), exception);
                cleanup(session.emitterId(), emitter, exception);
            }
        }
    }

    /**
     * 저장소에서 세션을 제거하고 Emitter를 종료(정상 완료 또는 에러 완료)하는 단일 정리 메서드.
     *
     * @param emitterId 제거할 Emitter 식별자
     * @param emitter 종료할 SseEmitter
     * @param error 전송 실패 예외 (정상 만료 시 null)
     */
    private void cleanup(String emitterId, SseEmitter emitter, Throwable error) {
        emitterRepository.deleteById(emitterId);
        try {
            if (error != null) {
                emitter.completeWithError(error);
            } else {
                emitter.complete();
            }
        } catch (Exception exception) {
            log.debug("Emitter 종료 처리 중 예외 무시: emitterId={}", emitterId, exception);
        }
    }

    private Long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException exception) {
            log.debug("유효하지 않은 Last-Event-ID 헤더 형식: {}", lastEventId);
            return null;
        }
    }

    private String generateEmitterId(Long memberId) {
        return memberId + "_" + clock.millis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
