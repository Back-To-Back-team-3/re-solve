package com.backtoback.common.scheduling.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.transaction.PlatformTransactionManager;

import net.javacrumbs.shedlock.core.LockProvider;

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.scheduling.scheduler.OutboxCleanupScheduler;
import com.backtoback.common.scheduling.scheduler.ProcessedEventCleanupScheduler;
import com.backtoback.common.scheduling.service.EventRecordCleanupService;

/**
 * 실제 Spring 예약 등록 경로에서 작업별 독립 활성화·중복 설정 공유·서울 시간 cron을 검증한다.
 * 실행기는 Mock으로 대체해 테스트 중 예약된 DB 삭제가 시작되지 않도록 한다.
 */
class EventCleanupConfigurationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T16:59:00Z"), ZoneOffset.UTC);

    private TaskScheduler taskScheduler;

    /**
     * 예약만 기록하고 실제 Runnable은 실행하지 않는 실행기를 준비한다.
     */
    @BeforeEach
    void setUp() {
        taskScheduler = mock(TaskScheduler.class);
        given(taskScheduler.getClock()).willReturn(CLOCK);
        willReturn(mock(ScheduledFuture.class)).given(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    /**
     * 저장 기능만 필요한 서비스에서 잠금·예약 실행·Relay가 함께 켜지지 않는지 확인한다.
     */
    @Test
    @DisplayName("정리 저장 설정만 등록하면 예약 작업과 잠금 공급자를 활성화하지 않는다")
    void keepsStorageIndependentOfSchedulingAndLocks() {
        // when
        try (var context = openContext(EventCleanupStorageConfiguration.class)) {
            // then
            assertThat(context.getBeansOfType(EventRecordCleanupService.class)).hasSize(1);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(LockProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(OutboxRelay.class)).isEmpty();
        }
    }

    /**
     * 발행 정리만 선택했을 때 소비 정리를 켜지 않고 UTC 실행기에서도 서울 02시를 예약하는지 확인한다.
     */
    @Test
    @DisplayName("Outbox 정리 설정은 발행 정리 한 건만 서울 시간 02시에 예약한다")
    void activatesOnlyOutboxCleanupAtSeoulTwoOClock() {
        // when
        try (var context = openContext(OutboxCleanupConfiguration.class)) {
            // then
            assertThat(context.getBeansOfType(OutboxCleanupScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(ProcessedEventCleanupScheduler.class)).isEmpty();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            var trigger = ArgumentCaptor.forClass(Trigger.class);
            verify(taskScheduler).schedule(any(Runnable.class), trigger.capture());
            assertThat(trigger.getValue()).isInstanceOfSatisfying(CronTrigger.class, cron -> {
                assertThat(cron.getExpression()).isEqualTo("0 0 2 * * *");
                assertThat(cron.nextExecution(new SimpleTriggerContext(CLOCK)))
                    .isEqualTo(Instant.parse("2026-10-29T17:00:00Z"));
            });
        }
    }

    /**
     * Outbox가 없는 소비 서비스도 소비 정리만 선택하고 서울 02시 10분을 예약할 수 있는지 확인한다.
     */
    @Test
    @DisplayName("소비 기록 정리 설정은 소비 정리 한 건만 서울 시간 02시 10분에 예약한다")
    void activatesOnlyProcessedCleanupAtSeoulTwoTen() {
        // when
        try (var context = openContext(ProcessedEventCleanupConfiguration.class)) {
            // then
            assertThat(context.getBeansOfType(ProcessedEventCleanupScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(OutboxCleanupScheduler.class)).isEmpty();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            var trigger = ArgumentCaptor.forClass(Trigger.class);
            verify(taskScheduler).schedule(any(Runnable.class), trigger.capture());
            assertThat(trigger.getValue()).isInstanceOfSatisfying(CronTrigger.class, cron -> {
                assertThat(cron.getExpression()).isEqualTo("0 10 2 * * *");
                assertThat(cron.nextExecution(new SimpleTriggerContext(CLOCK)))
                    .isEqualTo(Instant.parse("2026-10-29T17:10:00Z"));
            });
        }
    }

    /**
     * 두 기능을 선택한 서비스에서 공통 저장 설정과 잠금 공급자가 중복 생성되지 않는지 확인한다.
     */
    @Test
    @DisplayName("두 정리 설정을 함께 등록하면 작업 두 건과 공통 잠금 공급자 한 개를 구성한다")
    void sharesStorageAndLockProviderForBothJobs() {
        // when
        try (var context = openContext(OutboxCleanupConfiguration.class, ProcessedEventCleanupConfiguration.class)) {
            // then
            assertThat(context.getBeansOfType(LockProvider.class)).hasSize(1);
            assertThat(context.getBeansOfType(EventRecordCleanupService.class)).hasSize(1);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(2);
            var triggers = ArgumentCaptor.forClass(Trigger.class);
            verify(taskScheduler, times(2)).schedule(any(Runnable.class), triggers.capture());
            assertThat(triggers.getAllValues())
                .allSatisfy(trigger -> assertThat(trigger).isInstanceOf(CronTrigger.class));
            assertThat(triggers.getAllValues())
                .extracting(trigger -> ((CronTrigger)trigger).getExpression())
                .containsExactlyInAnyOrder("0 0 2 * * *", "0 10 2 * * *");
        }
    }

    /**
     * 서비스가 제공할 DB·트랜잭션 관리자와 테스트 전용 실행기를 명시적 설정에 연결한다.
     *
     * @param configurations 이 테스트에서 선택할 정리 설정
     * @return 예약 등록까지 완료한 컨텍스트
     */
    private AnnotationConfigApplicationContext openContext(Class<?>... configurations) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> mock(DataSource.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.registerBean("testClock", Clock.class, () -> CLOCK, bean -> bean.setPrimary(true));
        context.registerBean("taskScheduler", TaskScheduler.class, () -> taskScheduler);
        context.registerBean(SchedulingConfigurer.class, () -> tasks -> tasks.setTaskScheduler(taskScheduler));
        context.register(configurations);
        context.refresh();
        return context;
    }
}
