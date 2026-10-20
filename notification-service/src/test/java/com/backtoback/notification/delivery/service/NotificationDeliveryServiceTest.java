package com.backtoback.notification.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

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
}
