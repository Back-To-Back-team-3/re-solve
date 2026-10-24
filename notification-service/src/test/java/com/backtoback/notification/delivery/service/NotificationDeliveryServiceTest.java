package com.backtoback.notification.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.delivery.repository.EmitterRepositoryImpl;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ClockConfiguration.SERVICE_ZONE);

    private EmitterRepository emitterRepository;

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        emitterRepository = new EmitterRepositoryImpl();
        deliveryService = new NotificationDeliveryService(emitterRepository, notificationRepository, clock);
    }

    @Test
    @DisplayName("최초 구독 시 Emitter가 정상 등록되고 저장소에 보관된다")
    void subscribeInitialConnection() {
        // given
        Long memberId = 1L;

        // when
        SseEmitter emitter = deliveryService.subscribe(memberId, null);

        // then
        assertThat(emitter).isNotNull();
        List<SseEmitter> emitters = emitterRepository.findAllByMemberId(memberId);
        assertThat(emitters).hasSize(1);
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
    }

    @Test
    @DisplayName("하트비트 브로드캐스트 시 모든 활성 Emitter에 하트비트를 전송한다")
    void broadcastHeartbeat() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);

        // when & then (예외 없이 실행됨을 확인)
        deliveryService.broadcastHeartbeat();
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);
    }

    @Test
    @DisplayName("연결 유지 시간이 초과된 Emitter는 정상 완료되고 저장소에서 제거된다")
    void closeExpiredConnections() {
        // given
        Long memberId = 1L;
        deliveryService.subscribe(memberId, null);
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);

        // when (0ms 기준 만료 실행)
        deliveryService.closeExpiredConnections(0L);

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
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

        // when & then (예외 없이 전송 완료)
        deliveryService.sendToMember(memberId, notification);
        assertThat(emitterRepository.findAllByMemberId(memberId)).hasSize(1);
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
        CountDownLatch realtimeSendTriggeredLatch = new CountDownLatch(1);

        given(
            notificationRepository
                .findByMemberIdAndIdGreaterThanAndIsReadFalseAndCreatedAtAfterOrderByIdAsc(
                    eq(memberId),
                    eq(100L),
                    any()
                )
        ).willAnswer(invocation -> {
            replayStartedLatch.countDown();
            // 실시간 발송 스레드가 sendToMember()를 호출할 수 있도록 대기
            realtimeSendTriggeredLatch.await(2, TimeUnit.SECONDS);
            return List.of(missed1, missed2);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> realtimeFuture = executor.submit(() -> {
            try {
                replayStartedLatch.await(2, TimeUnit.SECONDS);
                deliveryService.sendToMember(memberId, realtime3);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                realtimeSendTriggeredLatch.countDown();
            }
        });

        // when
        SseEmitter emitter = deliveryService.subscribe(memberId, lastEventId);
        realtimeFuture.get(3, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        @SuppressWarnings("unchecked") Set<?> earlySendAttempts
            = (Set<?>)ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertThat(earlySendAttempts).isNotNull();

        List<String> idList = earlySendAttempts.stream().map(attempt -> {
            Object data = ReflectionTestUtils.getField(attempt, "data");
            return data != null ? data.toString() : "";
        }).filter(text -> text.startsWith("id:")).map(text -> text.split("\n")[0].replace("id:", "").trim()).toList();

        // 101, 102 재전송 후 103 실시간 알림이 순서대로 전송되었는지 검증
        assertThat(idList).containsExactly("101", "102", "103");
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
        SseEmitter emitter = deliveryService.subscribe(memberId, lastEventId);

        // then: 동일한 101번 알림을 실시간으로 재발송 시도
        deliveryService.sendToMember(memberId, notification101);

        @SuppressWarnings("unchecked") Set<?> earlySendAttempts
            = (Set<?>)ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertThat(earlySendAttempts).isNotNull();

        List<String> idList = earlySendAttempts.stream().map(attempt -> {
            Object data = ReflectionTestUtils.getField(attempt, "data");
            return data != null ? data.toString() : "";
        }).filter(text -> text.startsWith("id:")).map(text -> text.split("\n")[0].replace("id:", "").trim()).toList();

        // 중복 전송되지 않고 총 1건만 유지되어야 함
        assertThat(idList).containsExactly("101");
    }
}
