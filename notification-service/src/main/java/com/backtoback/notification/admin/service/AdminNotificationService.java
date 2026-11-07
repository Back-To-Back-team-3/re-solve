package com.backtoback.notification.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.notification.admin.dto.request.AdminNotificationBroadcastRequest;
import com.backtoback.notification.admin.dto.response.AdminNotificationBroadcastResponse;
import com.backtoback.notification.delivery.repository.EmitterRepository;
import com.backtoback.notification.delivery.repository.SseSession;
import com.backtoback.notification.delivery.service.NotificationDeliveryService;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.notification.repository.NotificationWithdrawnMemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 전역 공지 알림 발송 및 브로드캐스트를 처리하는 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryService deliveryService;
    private final EmitterRepository emitterRepository;
    private final NotificationWithdrawnMemberRepository withdrawnMemberRepository;
    private final Clock clock;

    /**
     * 전체 사용자에게 시스템 공지 알림을 브로드캐스트 발송하고 보관함에 저장한다.
     *
     * @param adminMemberId 관리자 회원 ID
     * @param request 공지 발송 요청 DTO
     * @return 발송 결과 DTO
     */
    @Transactional
    public AdminNotificationBroadcastResponse broadcastSystemNotice(
        Long adminMemberId,
        AdminNotificationBroadcastRequest request
    ) {
        String sourceEventId = "notice-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. 관리자 본인 기록용 대표 공지 알림 저장
        Notification adminNotification
            = Notification
                .builder()
                .memberId(adminMemberId)
                .sourceEventId(sourceEventId)
                .type(NotificationType.SYSTEM_NOTICE)
                .title(request.title())
                .content(request.content())
                .linkUrl(request.linkUrl())
                .build();
        Notification savedAdminNotice = notificationRepository.save(adminNotification);

        // 2. 현재 활성 SSE 세션의 회원 목록을 수집하여 각 회원별 보관함 알림 저장
        List<SseSession> activeSessions = emitterRepository.findAll();
        Set<Long> targetMemberIds = new HashSet<>();
        for (SseSession session : activeSessions) {
            Long memberId = session.memberId();
            if (memberId != null && !memberId.equals(adminMemberId)) {
                if (withdrawnMemberRepository == null || !withdrawnMemberRepository.existsByMemberId(memberId)) {
                    targetMemberIds.add(memberId);
                }
            }
        }

        for (Long memberId : targetMemberIds) {
            Notification memberNotice
                = Notification
                    .builder()
                    .memberId(memberId)
                    .sourceEventId(sourceEventId)
                    .type(NotificationType.SYSTEM_NOTICE)
                    .title(request.title())
                    .content(request.content())
                    .linkUrl(request.linkUrl())
                    .build();
            notificationRepository.save(memberNotice);
        }

        // 3. 실시간 SSE 세션 전체에 브로드캐스트 전송
        int deliveredCount = deliveryService.broadcast(savedAdminNotice);

        log
            .info(
                "관리자 공지 브로드캐스트 완료: adminId={}, title={}, targetMembers={}, deliveredSseCount={}",
                adminMemberId,
                request.title(),
                targetMemberIds.size(),
                deliveredCount
            );

        return new AdminNotificationBroadcastResponse(
            NotificationType.SYSTEM_NOTICE,
            request.title(),
            now,
            deliveredCount
        );
    }
}
