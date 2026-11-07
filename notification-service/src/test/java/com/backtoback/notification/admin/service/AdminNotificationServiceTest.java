package com.backtoback.notification.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.notification.admin.dto.request.AdminNotificationBroadcastRequest;
import com.backtoback.notification.admin.dto.response.AdminNotificationBroadcastResponse;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.delivery.repository.SseSession;
import com.backtoback.notification.delivery.service.NotificationDeliveryService;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.notification.repository.NotificationWithdrawnMemberRepository;

class AdminNotificationServiceTest {

    private NotificationRepository notificationRepository;
    private NotificationDeliveryService deliveryService;
    private EmitterRepository emitterRepository;
    private NotificationWithdrawnMemberRepository withdrawnMemberRepository;
    private Clock fixedClock;
    private TransactionTemplate transactionTemplate;
    private AdminNotificationService adminNotificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        deliveryService = mock(NotificationDeliveryService.class);
        emitterRepository = mock(EmitterRepository.class);
        withdrawnMemberRepository = mock(NotificationWithdrawnMemberRepository.class);
        fixedClock = Clock.fixed(Instant.parse("2026-11-07T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        transactionTemplate = mock(TransactionTemplate.class);

        // TransactionTemplate이 넘겨받은 콜백을 동기 실행하도록 설정
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            TransactionStatus status = new SimpleTransactionStatus();
            return callback.doInTransaction(status);
        });

        adminNotificationService
            = new AdminNotificationService(
                notificationRepository,
                deliveryService,
                emitterRepository,
                withdrawnMemberRepository,
                fixedClock,
                transactionTemplate
            );
    }

    @Test
    @DisplayName("관리자 공지 발송 시 각 수신 회원별로 고유한 Notification 엔티티를 생성하고, 회원별 맵으로 브로드캐스트를 전송한다")
    @SuppressWarnings("unchecked")
    void broadcastSystemNoticeSuccess() {
        // given
        Long adminId = 1L;
        Long activeMemberId = 2L;
        Long withdrawnMemberId = 3L;

        SseSession adminSession = SseSession.create("emitter-1", adminId, new SseEmitter(), 1000L, 0L);
        SseSession activeMemberSession = SseSession.create("emitter-2", activeMemberId, new SseEmitter(), 1000L, 0L);
        SseSession withdrawnSession = SseSession.create("emitter-3", withdrawnMemberId, new SseEmitter(), 1000L, 0L);

        given(emitterRepository.findAll()).willReturn(List.of(adminSession, activeMemberSession, withdrawnSession));
        given(withdrawnMemberRepository.existsByMemberId(activeMemberId)).willReturn(false);
        given(withdrawnMemberRepository.existsByMemberId(withdrawnMemberId)).willReturn(true);
        given(notificationRepository.save(any(Notification.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(deliveryService.broadcast(any(Map.class))).willReturn(2);

        AdminNotificationBroadcastRequest request
            = new AdminNotificationBroadcastRequest("긴급 점검 공지", "30분간 긴급 점검이 진행됩니다.", "/notices/99");

        // when
        AdminNotificationBroadcastResponse response = adminNotificationService.broadcastSystemNotice(adminId, request);

        // then
        assertThat(response.notificationType()).isEqualTo(NotificationType.SYSTEM_NOTICE);
        assertThat(response.title()).isEqualTo("긴급 점검 공지");
        assertThat(response.deliveredSseCount()).isEqualTo(2);

        // 관리자 본인 1회 + 활성 회원 1회 = 총 2회 저장 (탈퇴 회원은 제외)
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(notificationCaptor.capture());

        List<Notification> savedNotifications = notificationCaptor.getAllValues();
        assertThat(savedNotifications)
            .extracting(Notification::getMemberId)
            .containsExactlyInAnyOrder(adminId, activeMemberId);

        // deliveryService.broadcast에 회원별 알림 맵이 올바르게 전달되었는지 검증
        ArgumentCaptor<Map<Long, Notification>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deliveryService).broadcast(mapCaptor.capture());

        Map<Long, Notification> broadcastMap = mapCaptor.getValue();
        assertThat(broadcastMap).containsOnlyKeys(adminId, activeMemberId);
        assertThat(broadcastMap.get(adminId).getMemberId()).isEqualTo(adminId);
        assertThat(broadcastMap.get(activeMemberId).getMemberId()).isEqualTo(activeMemberId);
    }
}
