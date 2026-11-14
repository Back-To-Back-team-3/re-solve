package com.backtoback.notification.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.notification.notification.repository.NotificationRepository;
import com.backtoback.notification.reminder.repository.NotificationReminderRepository;

class NotificationRetentionServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-27T18:30:00Z"); // 2026-10-28T03:30:00 KST

    private NotificationRepository notificationRepository;
    private NotificationReminderRepository reminderRepository;
    private Clock clock;
    private NotificationRetentionService retentionService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        reminderRepository = mock(NotificationReminderRepository.class);
        clock = Clock.fixed(FIXED_INSTANT, ClockConfiguration.SERVICE_ZONE);
        retentionService = new NotificationRetentionService(notificationRepository, reminderRepository, clock);
    }

    @Test
    @DisplayName("7.2 현재 시각 기준 90일 이전 생성된 알림을 배치 크기만큼 삭제한다")
    void deleteExpiredNotificationsBatchDeletesOlderThan90Days() {
        // given
        LocalDateTime expectedCutoff = LocalDateTime.now(clock).minusDays(90);
        given(notificationRepository.deleteExpiredNotifications(expectedCutoff, 1000)).willReturn(420);

        // when
        int deleted = retentionService.deleteExpiredNotificationsBatch(1000);

        // then
        assertThat(deleted).isEqualTo(420);
        verify(notificationRepository).deleteExpiredNotifications(expectedCutoff, 1000);
    }

    @Test
    @DisplayName("7.2 현재 시각 기준 7일 이전 종결된(SENT·CANCELED) 실제 참가 예약을 배치 크기만큼 삭제한다")
    void deleteExpiredRemindersBatchDeletesOlderThan7Days() {
        // given
        LocalDateTime expectedCutoff = LocalDateTime.now(clock).minusDays(7);
        given(reminderRepository.deleteExpiredCompletedReminders(expectedCutoff, 1000)).willReturn(85);

        // when
        int deleted = retentionService.deleteExpiredRemindersBatch(1000);

        // then
        assertThat(deleted).isEqualTo(85);
        verify(reminderRepository).deleteExpiredCompletedReminders(expectedCutoff, 1000);
    }

    @Test
    @DisplayName("7.2 현재 시각 기준 14일 이전(SQS 최대 보관 기간 경과) 음수 표식 행을 배치 크기만큼 삭제한다")
    void deleteExpiredSentinelRemindersBatchDeletesOlderThan14Days() {
        // given
        LocalDateTime expectedCutoff = LocalDateTime.now(clock).minusDays(14);
        given(reminderRepository.deleteExpiredSentinelReminders(expectedCutoff, 1000)).willReturn(12);

        // when
        int deleted = retentionService.deleteExpiredSentinelRemindersBatch(1000);

        // then
        assertThat(deleted).isEqualTo(12);
        verify(reminderRepository).deleteExpiredSentinelReminders(expectedCutoff, 1000);
    }
}
