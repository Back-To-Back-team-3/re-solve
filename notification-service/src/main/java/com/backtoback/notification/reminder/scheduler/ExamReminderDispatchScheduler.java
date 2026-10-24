package com.backtoback.notification.reminder.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.notification.reminder.domain.NotificationReminder;
import com.backtoback.notification.reminder.service.ExamReminderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시험 시작 5분 전 예약 알림을 10초 주기로 발화하는 스케줄러 (스케줄러 명세서 0.1, 7.1).
 * 대상 1건당 독립 트랜잭션(타임아웃 1초)으로 처리하여 개별 실패가 회차 전체를 롤백하지 않도록 격리하며,
 * 한 회차 대상 수 상한(100건)과 실행 시간 상한(4.5초, ShedLock 9초의 절반)을 함께 적용한다.
 * 이미 진입한 건이 타임아웃(1초)까지 실행되어도 회차 상한(4.5초)을 넘지 않도록 시작 후 3.5초까지만 신규 작업을 시작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExamReminderDispatchScheduler {

    public static final int BATCH_LIMIT = 100;
    public static final Duration MAX_EXECUTION_DURATION = Duration.ofMillis(4500);
    public static final Duration SINGLE_ITEM_TIMEOUT
        = Duration.ofSeconds(ExamReminderService.SINGLE_OPERATION_TIMEOUT_SECONDS);
    public static final Duration SELECTION_WINDOW = MAX_EXECUTION_DURATION.minus(SINGLE_ITEM_TIMEOUT);

    private final ExamReminderService examReminderService;
    private final Clock clock;

    /**
     * 10초 주기로 만료된 예약을 정리하고, 발화 시각이 도래한 예약을 조회하여 실행 시간 상한(4.5초) 내에서 건별로 발화한다.
     * 상한 도달로 처리하지 못한 남은 대상은 다음 회차에서 시각·상태 조건으로 다시 선정한다 (스케줄러 명세서 0.1).
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
        Instant selectionDeadline = clock.instant().plus(SELECTION_WINDOW);

        int canceledCount = examReminderService.cancelExpiredReminders();
        if (canceledCount > 0) {
            log.info("시험 시작 시각 경과로 만료된 예약 알림 취소 완료: count={}", canceledCount);
        }

        if (!clock.instant().isBefore(selectionDeadline)) {
            log.info("예약 알림 스케줄러 실행 시간 상한 도달로 발화 조회 생략 (다음 회차 이관)");
            return;
        }

        List<NotificationReminder> dueReminders = examReminderService.findDueReminders(BATCH_LIMIT);
        if (dueReminders.isEmpty()) {
            return;
        }

        int dispatchedCount = 0;
        for (NotificationReminder reminder : dueReminders) {
            if (!clock.instant().isBefore(selectionDeadline)) {
                log
                    .info(
                        "예약 알림 스케줄러 실행 시간 상한 도달로 잔여 발화 중단 (다음 회차 이관): dispatchedCount={}, totalFetched={}",
                        dispatchedCount,
                        dueReminders.size()
                    );
                break;
            }

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
