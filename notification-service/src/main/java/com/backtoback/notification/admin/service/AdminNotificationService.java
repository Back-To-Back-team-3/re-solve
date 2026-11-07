package com.backtoback.notification.admin.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
@Transactional(readOnly = true)
public class AdminNotificationService {

    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryService deliveryService;
    private final EmitterRepository emitterRepository;
    private final NotificationWithdrawnMemberRepository withdrawnMemberRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * 발송 시점에 활성화된 SSE 연결 회원(및 발송 관리자)에게 시스템 공지 알림을 브로드캐스트하고 보관함에 저장한다.
     * DB 트랜잭션 완료 후 트랜잭션 외부에서 SSE 네트워크 I/O를 수행하여 커넥션 점유를 방지한다.
     *
     * @param adminMemberId 관리자 회원 ID
     * @param request 공지 발송 요청 DTO
     * @return 발송 결과 DTO
     */
    public AdminNotificationBroadcastResponse broadcastSystemNotice(
        Long adminMemberId,
        AdminNotificationBroadcastRequest request
    ) {
        String sourceEventId = "notice-" + UUID.randomUUID();
        OffsetDateTime now
            = OffsetDateTime.now(clock).withOffsetSameInstant(KST_ZONE.getRules().getOffset(clock.instant()));

        // 1. 현재 활성 SSE 세션의 회원 목록을 수집 (탈퇴 회원 제외)
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

        // 2. DB 트랜잭션: 관리자 및 활성 회원별 알림 레코드를 독립적으로 저장하고 커밋
        Map<Long, Notification> savedNoticesByMemberId = transactionTemplate.execute(status -> {
            Map<Long, Notification> map = new HashMap<>();

            // 관리자 본인 기록용 공지 알림
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
            map.put(adminMemberId, notificationRepository.save(adminNotification));

            // 활성 회원별 개별 공지 알림 (각 회원 본인 ID가 할당됨)
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
                map.put(memberId, notificationRepository.save(memberNotice));
            }
            return map;
        });

        // 3. DB 트랜잭션 외부: 각 회원의 세션으로 해당 회원의 Notification 객체를 실시간 브로드캐스트 전송
        int deliveredCount = deliveryService.broadcast(savedNoticesByMemberId);

        log
            .info(
                "관리자 공지 브로드캐스트 완료: adminId={}, title={}, targetMembers={}, deliveredSseCount={}",
                adminMemberId,
                request.title(),
                targetMemberIds.size(),
                deliveredCount
            );

        return AdminNotificationBroadcastResponse
            .of(NotificationType.SYSTEM_NOTICE, request.title(), now, deliveredCount);
    }
}
