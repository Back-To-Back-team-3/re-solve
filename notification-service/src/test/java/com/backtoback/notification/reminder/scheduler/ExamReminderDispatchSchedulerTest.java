package com.backtoback.notification.reminder.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.service.ExamReminderService;

class ExamReminderDispatchSchedulerTest {

    private ExamReminderService examReminderService;
    private ExamReminderDispatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        examReminderService = mock(ExamReminderService.class);
        scheduler = new ExamReminderDispatchScheduler(examReminderService);
    }

    @Test
    @DisplayName("7.1 스케줄러 실행 시 만료된 예약을 먼저 취소하고 발화 대상 예약을 건별로 발화한다")
    void dispatchRemindersCancelsExpiredAndDispatchesDueReminders() {
        // given
        NotificationReminder reminder1 = createReminder(1L, 411L);
        NotificationReminder reminder2 = createReminder(2L, 412L);

        given(examReminderService.cancelExpiredReminders()).willReturn(1);
        given(examReminderService.findDueReminders(ExamReminderDispatchScheduler.BATCH_LIMIT))
            .willReturn(List.of(reminder1, reminder2));
        given(examReminderService.dispatchSingleReminder(reminder1)).willReturn(true);
        given(examReminderService.dispatchSingleReminder(reminder2)).willReturn(true);

        // when
        scheduler.dispatchReminders();

        // then
        verify(examReminderService).cancelExpiredReminders();
        verify(examReminderService).findDueReminders(ExamReminderDispatchScheduler.BATCH_LIMIT);
        verify(examReminderService).dispatchSingleReminder(reminder1);
        verify(examReminderService).dispatchSingleReminder(reminder2);
    }

    @Test
    @DisplayName("0.1 개별 예약 발화 중 예외가 발생해도 회차 전체가 중단되지 않고 다음 예약을 계속 처리한다")
    void dispatchRemindersWhenSingleItemFailsContinuesRemainingItems() {
        // given
        NotificationReminder failingReminder = createReminder(1L, 411L);
        NotificationReminder succeedingReminder = createReminder(2L, 412L);

        given(examReminderService.cancelExpiredReminders()).willReturn(0);
        given(examReminderService.findDueReminders(ExamReminderDispatchScheduler.BATCH_LIMIT))
            .willReturn(List.of(failingReminder, succeedingReminder));
        given(examReminderService.dispatchSingleReminder(failingReminder)).willThrow(new RuntimeException("DB 일시 오류"));
        given(examReminderService.dispatchSingleReminder(succeedingReminder)).willReturn(true);

        // when
        scheduler.dispatchReminders();

        // then
        verify(examReminderService).dispatchSingleReminder(failingReminder);
        verify(examReminderService).dispatchSingleReminder(succeedingReminder);
    }

    private NotificationReminder createReminder(Long id, Long participantId) {
        NotificationReminder reminder
            = NotificationReminder
                .createScheduled(7L, 55L, participantId, LocalDateTime.of(2026, 10, 20, 20, 3, 0), 1, "evt-1");
        ReflectionTestUtils.setField(reminder, "id", id);
        return reminder;
    }
}
