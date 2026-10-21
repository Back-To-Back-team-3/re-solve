package com.backtoback.notification.notification.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.notification.global.error.NotificationException;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.dto.response.NotificationPageResponse;
import com.backtoback.notification.notification.dto.response.UnreadCountResponse;
import com.backtoback.notification.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원별 알림 목록 조회 및 미읽음 알림 개수 조회를 담당하는 서비스.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String ALLOWED_SORT_PROPERTY = "createdAt";

    private final NotificationRepository notificationRepository;

    /**
     * 회원의 알림 목록을 페이징 조회하고 현재 미읽음 알림 개수를 함께 반환한다 (API 명세서 7.1.2).
     * 허용되지 않은 정렬 필드가 전달되거나 페이지 크기가 100을 초과하면 {@code COMMON_INVALID_REQUEST} 예외를 던진다 (API 명세서 0.1).
     *
     * @param memberId 회원 ID
     * @param unreadOnly true일 경우 미읽음 알림만 필터링하여 조회
     * @param pageable 페이징 및 정렬 정보 (size 최대 100, sort는 createdAt만 허용)
     * @return 알림 페이징 목록과 미읽음 개수 응답
     */
    public NotificationPageResponse getNotifications(Long memberId, boolean unreadOnly, Pageable pageable) {
        validatePageable(pageable);

        Page<Notification> notificationPage
            = unreadOnly ? notificationRepository.findByMemberIdAndIsReadFalse(memberId, pageable)
                : notificationRepository.findByMemberId(memberId, pageable);

        long unreadCount = notificationRepository.countByMemberIdAndIsReadFalse(memberId);

        return NotificationPageResponse.of(notificationPage, unreadCount);
    }

    /**
     * 회원의 미읽음 알림 총 개수를 조회한다 (API 명세서 7.1.3).
     *
     * @param memberId 회원 ID
     * @return 미읽음 알림 개수 응답
     */
    public UnreadCountResponse getUnreadCount(Long memberId) {
        long unreadCount = notificationRepository.countByMemberIdAndIsReadFalse(memberId);
        return new UnreadCountResponse(unreadCount);
    }

    private void validatePageable(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE || pageable.getPageSize() < 1 || pageable.getPageNumber() < 0) {
            throw new NotificationException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        validateSort(pageable.getSort());
    }

    private void validateSort(Sort sort) {
        if (sort.isUnsorted()) {
            return;
        }
        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_PROPERTY.equals(order.getProperty())) {
                throw new NotificationException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
        }
    }
}
