package com.backtoback.notification.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.notification.global.error.NotificationException;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.dto.response.NotificationPageResponse;
import com.backtoback.notification.notification.dto.response.UnreadCountResponse;
import com.backtoback.notification.notification.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository);
    }

    @Test
    @DisplayName("unreadOnly가 false이면 전체 알림 목록을 페이징 조회하고 미읽음 개수를 함께 반환한다")
    void getNotificationsAll() {
        // given
        Long memberId = 1L;
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        Notification notification
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-1")
                .type(NotificationType.SUBMISSION_JUDGED)
                .title("채점 완료")
                .content("1001번 문제 채점이 완료되었습니다.")
                .linkUrl("/submissions/1")
                .build();
        ReflectionTestUtils.setField(notification, "id", 100L);

        Page<Notification> page = new PageImpl<>(List.of(notification), pageable, 1);

        given(notificationRepository.findByMemberId(eq(memberId), eq(pageable))).willReturn(page);
        given(notificationRepository.countByMemberIdAndIsReadFalse(eq(memberId))).willReturn(3L);

        // when
        NotificationPageResponse response = notificationService.getNotifications(memberId, false, pageable);

        // then
        assertThat(response.unreadCount()).isEqualTo(3L);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).notificationId()).isEqualTo("100");
        assertThat(response.content().get(0).title()).isEqualTo("채점 완료");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1L);

        verify(notificationRepository).findByMemberId(eq(memberId), eq(pageable));
        verify(notificationRepository).countByMemberIdAndIsReadFalse(eq(memberId));
    }

    @Test
    @DisplayName("unreadOnly가 true이면 미읽음 알림만 페이징 조회하고 미읽음 개수를 함께 반환한다")
    void getNotificationsUnreadOnly() {
        // given
        Long memberId = 1L;
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        Notification notification
            = Notification
                .builder()
                .memberId(memberId)
                .sourceEventId("evt-2")
                .type(NotificationType.ASSIGNMENT_DEADLINE_APPROACHING)
                .title("과제 마감 임박")
                .content("마감 1시간 전입니다.")
                .build();
        ReflectionTestUtils.setField(notification, "id", 101L);

        Page<Notification> page = new PageImpl<>(List.of(notification), pageable, 1);

        given(notificationRepository.findByMemberIdAndIsReadFalse(eq(memberId), eq(pageable))).willReturn(page);
        given(notificationRepository.countByMemberIdAndIsReadFalse(eq(memberId))).willReturn(1L);

        // when
        NotificationPageResponse response = notificationService.getNotifications(memberId, true, pageable);

        // then
        assertThat(response.unreadCount()).isEqualTo(1L);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).notificationId()).isEqualTo("101");

        verify(notificationRepository).findByMemberIdAndIsReadFalse(eq(memberId), eq(pageable));
        verify(notificationRepository).countByMemberIdAndIsReadFalse(eq(memberId));
    }

    @Test
    @DisplayName("허용되지 않은 정렬 필드로 조회 시 COMMON_INVALID_REQUEST 예외를 던진다")
    void getNotificationsInvalidSortThrowsException() {
        // given
        Long memberId = 1L;
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "title"));

        // when & then
        assertThatThrownBy(() -> notificationService.getNotifications(memberId, false, pageable))
            .isInstanceOf(NotificationException.class)
            .extracting(exception -> ((NotificationException)exception).getErrorCode())
            .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    @DisplayName("페이지 크기가 100을 초과하면 COMMON_INVALID_REQUEST 예외를 던진다")
    void getNotificationsSizeExceedsMaxThrowsException() {
        // given
        Long memberId = 1L;
        Pageable pageable = PageRequest.of(0, 101, Sort.by(Sort.Direction.DESC, "createdAt"));

        // when & then
        assertThatThrownBy(() -> notificationService.getNotifications(memberId, false, pageable))
            .isInstanceOf(NotificationException.class)
            .extracting(exception -> ((NotificationException)exception).getErrorCode())
            .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    @DisplayName("페이지 크기가 1 미만이면 COMMON_INVALID_REQUEST 예외를 던진다")
    void getNotificationsSizeLessThanOneThrowsException() {
        // given
        Long memberId = 1L;
        Pageable pageable = mock(Pageable.class);
        given(pageable.getPageSize()).willReturn(0);

        // when & then
        assertThatThrownBy(() -> notificationService.getNotifications(memberId, false, pageable))
            .isInstanceOf(NotificationException.class)
            .extracting(exception -> ((NotificationException)exception).getErrorCode())
            .isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    @DisplayName("미읽음 알림 개수 조회 시 회원의 미읽음 개수를 정상 반환한다")
    void getUnreadCount() {
        // given
        Long memberId = 1L;
        given(notificationRepository.countByMemberIdAndIsReadFalse(eq(memberId))).willReturn(5L);

        // when
        UnreadCountResponse response = notificationService.getUnreadCount(memberId);

        // then
        assertThat(response.unreadCount()).isEqualTo(5L);
        verify(notificationRepository).countByMemberIdAndIsReadFalse(eq(memberId));
    }
}
