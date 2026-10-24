package com.backtoback.notification.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.delivery.repository.EmitterRepositoryImpl;
import com.backtoback.notification.delivery.repository.SseSession;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    private static final Instant INITIAL_INSTANT = Instant.parse("2026-10-20T12:00:00Z");

    private MutableClock clock;
    private EmitterRepository emitterRepository;

    @Mock
    private NotificationRepository notificationRepository;

    private CapturingSseEmitter latestEmitter;
    private NotificationDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(INITIAL_INSTANT, ClockConfiguration.SERVICE_ZONE);
        emitterRepository = new EmitterRepositoryImpl();
        deliveryService = new NotificationDeliveryService(emitterRepository, notificationRepository, clock) {
            @Override
            SseEmitter createEmitter() {
                latestEmitter = new CapturingSseEmitter();
                return latestEmitter;
            }
        };
    }

    @Test
    @DisplayName("최초 구독 시 Emitter가 정상 등록되고 초기 하트비트가 전송되며 저장소에 보관된다")
    void subscribeInitialConnection() {
        // given
        Long memberId = 1L;

        // when
        SseEmitter emitter = deliveryService.subscribe(memberId, null);

        // then
        assertThat(emitter).isNotNull();
        List<SseSession> sessions = emitterRepository.findAllByMemberId(memberId);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).creationTime()).isEqualTo(INITIAL_INSTANT.toEpochMilli());
    }

    @Test
    @DisplayName("Last-Event-ID가 전달되면 최근 24시간 내 미수신 알림을 조회하여 재전송한다")
    void subscribeWithLastEventIdReplaysMissedNotifications() {
        // given
        Long memberId = 1L;
        String lastEventId = "100";

        Notification missedNotification
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-1")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("1001번 문제 채점이 완료되었습니다.")
                .build();
        ReflectionTestUtils.setField(missedNotification, "id", 101L);

        LocalDateTime expectedAfter = LocalDateTime.now(clock).minusHours(24);

        given(
            notificationRepository
                .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                    eq(memberId),
                    eq(100L),
                    eq(expectedAfter)
                )
        ).willReturn(List.of(missedNotification));

        // when
        SseEmitter emitter = deliveryService.subscribe(memberId, lastEventId);

        // then
        assertThat(emitter).isNotNull();
        verify(notificationRepository)
            .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                eq(memberId),
                eq(100L),
                eq(expectedAfter)
            );
        assertThat(latestEmitter.getSentNotificationIds()).containsExactly("101");
    }

    @Test
    @DisplayName("하트비트 브로드캐스트 시 모든 활성 Emitter에 하트비트를 전송한다")
    void broadcastHeartbeat() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);

        // when & then
        deliveryService.broadcastHeartbeat();
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);
    }

    @Test
    @DisplayName("연결 유지 시간이 만료 기준 미만(59,999ms)이면 유지되고 기준 이상(60,000ms)이면 완료 후 제거된다")
    void closeExpiredConnectionsBoundary() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);

        // when 1: 59,999ms 경과 시점 (만료 전)
        clock.advanceMillis(59_999L);
        deliveryService.closeExpiredConnections(NotificationDeliveryService.SSE_TIMEOUT_MILLIS);

        // then 1: 세션 유지
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);
        assertThat(latestEmitter.isCompleted()).isFalse();

        // when 2: 1ms 추가 경과 (총 60,000ms 도달)
        clock.advanceMillis(1L);
        deliveryService.closeExpiredConnections(NotificationDeliveryService.SSE_TIMEOUT_MILLIS);

        // then 2: 정상 완료 및 저장소 제거
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
        assertThat(latestEmitter.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("회원에게 새 알림 전송 시 등록된 세션으로 전송된다")
    void sendToMember() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);

        Notification notification
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-2")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("채점 완료")
                .build();
        ReflectionTestUtils.setField(notification, "id", 200L);

        // when
        deliveryService.sendToMember(memberId, notification);

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);
        assertThat(latestEmitter.getSentNotificationIds()).containsExactly("200");
    }

    @Test
    @DisplayName("실시간 알림 전송 실패 시 세션이 저장소에서 제거되고 completeWithError가 호출된다")
    void sendToMemberCleansUpSessionOnFailure() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);
        latestEmitter.setFailOnSend(true);

        Notification notification
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-fail")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("채점 완료")
                .build();
        ReflectionTestUtils.setField(notification, "id", 201L);

        // when
        deliveryService.sendToMember(memberId, notification);

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
        assertThat(latestEmitter.getCompletedError()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("하트비트 전송 실패 시 세션이 저장소에서 제거되고 completeWithError가 호출된다")
    void broadcastHeartbeatCleansUpSessionOnFailure() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);
        latestEmitter.setFailOnSend(true);

        // when
        deliveryService.broadcastHeartbeat();

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
        assertThat(latestEmitter.getCompletedError()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("미수신 알림 재전송 중 전송 실패 시 세션이 저장소에서 제거되고 completeWithError가 호출된다")
    void replayMissedNotificationsCleansUpSessionOnFailure() {
        // given
        Long memberId = 1L;
        String lastEventId = "100";

        Notification missedNotification
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-replay-fail")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("채점 완료")
                .build();
        ReflectionTestUtils.setField(missedNotification, "id", 101L);

        given(
            notificationRepository
                .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                    eq(memberId),
                    eq(100L),
                    any()
                )
        ).willReturn(List.of(missedNotification));

        deliveryService = new NotificationDeliveryService(emitterRepository, notificationRepository, clock) {
            @Override
            SseEmitter createEmitter() {
                latestEmitter = new CapturingSseEmitter();
                latestEmitter.setFailOnSend(true);
                return latestEmitter;
            }
        };

        // when
        deliveryService.subscribe(memberId, lastEventId);

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
        assertThat(latestEmitter.getCompletedError()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("재전송과 실시간 발송이 겹칠 때 재전송 알림이 먼저 발송되고 실시간 알림이 뒤이어 순서대로 발송된다")
    void replayAndRealtimeConcurrentDispatchOrderPreserved() throws Exception {
        // given
        Long memberId = 1L;
        String lastEventId = "100";

        Notification missed1
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-1")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("과거 알림 101")
                .content("101")
                .build();
        ReflectionTestUtils.setField(missed1, "id", 101L);

        Notification missed2
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-2")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("과거 알림 102")
                .content("102")
                .build();
        ReflectionTestUtils.setField(missed2, "id", 102L);

        Notification realtime3
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-3")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("실시간 알림 103")
                .content("103")
                .build();
        ReflectionTestUtils.setField(realtime3, "id", 103L);

        CountDownLatch replayStartedLatch = new CountDownLatch(1);
        CountDownLatch realtimeSendEnteredLatch = new CountDownLatch(1);

        emitterRepository = new EmitterRepositoryImpl() {
            @Override
            public List<SseSession> findAllByMemberId(Long targetMemberId) {
                List<SseSession> sessions = super.findAllByMemberId(targetMemberId);
                realtimeSendEnteredLatch.countDown();
                return sessions;
            }
        };
        deliveryService = new NotificationDeliveryService(emitterRepository, notificationRepository, clock) {
            @Override
            SseEmitter createEmitter() {
                latestEmitter = new CapturingSseEmitter();
                return latestEmitter;
            }
        };

        given(
            notificationRepository
                .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                    eq(memberId),
                    eq(100L),
                    any()
                )
        ).willAnswer(invocation -> {
            replayStartedLatch.countDown();
            assertThat(realtimeSendEnteredLatch.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(missed1, missed2);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> realtimeFuture = executor.submit(() -> {
            assertThat(replayStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
            deliveryService.sendToMember(memberId, realtime3);
            return null;
        });

        // when
        deliveryService.subscribe(memberId, lastEventId);
        realtimeFuture.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 101, 102 재전송 후 103 실시간 알림이 순서대로 전송되었는지 검증
        assertThat(latestEmitter.getSentNotificationIds()).containsExactly("101", "102", "103");
    }

    @Test
    @DisplayName("재전송 목록에 이미 포함되어 전송된 알림은 실시간 발송 시 중복 전송되지 않는다")
    void duplicateRealtimeNotificationIsNotSentAfterReplay() {
        // given
        Long memberId = 1L;
        String lastEventId = "100";

        Notification notification101
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-1")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("알림 101")
                .content("101")
                .build();
        ReflectionTestUtils.setField(notification101, "id", 101L);

        given(
            notificationRepository
                .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                    eq(memberId),
                    eq(100L),
                    any()
                )
        ).willReturn(List.of(notification101));

        // when: 101번이 재전송으로 이미 발송됨
        deliveryService.subscribe(memberId, lastEventId);

        // then: 동일한 101번 알림을 실시간으로 재발송 시도
        deliveryService.sendToMember(memberId, notification101);

        // 중복 전송되지 않고 총 1건만 유지되어야 함
        assertThat(latestEmitter.getSentNotificationIds()).containsExactly("101");
    }

    /**
     * 리플렉션 없이 전송 이벤트 ID와 종료 상태를 캡처하는 테스트용 SseEmitter 서브클래스.
     */
    private static final class CapturingSseEmitter extends SseEmitter {

        private final List<String> sentPayloads = new CopyOnWriteArrayList<>();
        private volatile boolean failOnSend;
        private volatile boolean completed;
        private volatile Throwable completedError;

        private CapturingSseEmitter() {
            super(NotificationDeliveryService.SSE_TIMEOUT_MILLIS);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("전송 실패 시뮬레이션");
            }
            super.send(builder);
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                text.append(item.getData());
            }
            sentPayloads.add(text.toString());
        }

        @Override
        public synchronized void complete() {
            this.completed = true;
            super.complete();
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            this.completedError = ex;
            super.completeWithError(ex);
        }

        private List<String> getSentNotificationIds() {
            return sentPayloads
                .stream()
                .filter(text -> text.startsWith("id:"))
                .map(text -> text.split("\n")[0].replace("id:", "").trim())
                .toList();
        }

        private void setFailOnSend(boolean failOnSend) {
            this.failOnSend = failOnSend;
        }

        private boolean isCompleted() {
            return completed;
        }

        private Throwable getCompletedError() {
            return completedError;
        }
    }

    /**
     * 만료 경계 테스트를 위해 시각을 이동할 수 있는 가변 Clock.
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> currentInstant;
        private final ZoneId zone;

        private MutableClock(Instant initialInstant, ZoneId zone) {
            this.currentInstant = new AtomicReference<>(initialInstant);
            this.zone = zone;
        }

        private void advanceMillis(long millis) {
            currentInstant.updateAndGet(instant -> instant.plusMillis(millis));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant.get(), zone);
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    }
}
