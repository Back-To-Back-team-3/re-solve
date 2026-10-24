package com.backtoback.notification.reminder.scheduler;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.service.ExamReminderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시험 시작 5분 전 예약 알림을 10초 주기로 발화하는 스케줄러 (스케줄러 명세서 7.1).
 * 대상 1건당 독립 트랜잭션으로 처리하여 개별 실패가 회차 전체를 롤백하지 않도록 격리한다 (스케줄러 명세서 0.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExamReminderDispatchScheduler {

    public static final int BATCH_LIMIT = 100;

    private final ExamReminderService examReminderService;

    /**
     * 10초 주기로 만료된 예약을 정리하고, 발화 시각이 도래한 예약을 조회하여 건별로 발화한다.
     */
    @Scheduled(
        fixedDelayString = "${notification.reminder.dispatch-fixed-delay-ms:10000}",
        initialDelayString = "${notification.reminder.dispatch-initial-delay-ms:10000}"
    )
    @SchedulerLock(
        name = "ExamReminderDispatchScheduler",
        lockAtMostFor = "PT9S"
    )
    public void dispatchReminders() {
        int canceledCount = examReminderService.cancelExpiredReminders();
        if (canceledCount > 0) {
            log.info("시험 시작 시각 경과로 만료된 예약 알림 취소 완료: count={}", canceledCount);
        }

        List<NotificationReminder> dueReminders = examReminderService.findDueReminders(BATCH_LIMIT);
        if (dueReminders.isEmpty()) {
            return;
        }

        int dispatchedCount = 0;
        for (NotificationReminder reminder : dueReminders) {
            try {
                if (examReminderService.dispatchSingleReminder(reminder)) {
                    dispatchedCount++;
                }
            } catch (Exception exception) {
                log
                    .warn(
                        "시험 시작 임박 예약 알림 개별 발화 실패: reminderId={}, participantId={}",
                        reminder.getId(),
                        reminder.getParticipantId(),
                        exception
                    );
            }
        }

        if (dispatchedCount > 0) {
            log.info("시험 시작 임박 예약 알림 발화 완료: dispatchedCount={}", dispatchedCount);
        }
    }
}
