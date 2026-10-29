package com.backtoback.common.event.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.PlatformTransactionManager;

import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.service.OutboxRelay;

import software.amazon.awssdk.services.sns.SnsClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 서비스의 명시적 Relay 설정 등록이 실제 예약 작업을 구성하는지 검증한다.
 * 예약 실행기는 Mock으로 대체해 테스트 중 DB 조회나 AWS 발행이 시작되지 않도록 한다.
 */
class OutboxRelayConfigurationTest {
    /**
     * Spring 예약 처리기가 Relay를 찾고 초기 지연과 반복 지연을 실제 실행기에 전달하는지 확인한다.
     */
    @Test
    @DisplayName("Relay 설정을 등록하면 초기 지연 5초와 반복 지연 1초의 예약 작업 한 건을 구성한다")
    void registersRelayScheduleWhenExplicitlyConfigured() {
        // given
        var dataSource = mock(DataSource.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        var snsClient = mock(SnsClient.class);
        var destinationResolver = mock(EventDestinationResolver.class);
        var taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        var clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
        var firstExecution = clock.instant().plusSeconds(5);
        given(taskScheduler.getClock()).willReturn(clock);
        willReturn(scheduledFuture)
            .given(taskScheduler)
            .scheduleWithFixedDelay(any(Runnable.class), eq(firstExecution), eq(Duration.ofSeconds(1)));

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
            context.registerBean(SnsClient.class, () -> snsClient);
            context.registerBean(EventDestinationResolver.class, () -> destinationResolver);
            context.registerBean("taskScheduler", TaskScheduler.class, () -> taskScheduler);
            // 기본 라우터의 시스템 Clock 대신 테스트 실행기의 고정 Clock으로 초기 지연을 계산한다.
            context.registerBean(SchedulingConfigurer.class, () -> tasks -> tasks.setTaskScheduler(taskScheduler));
            context.registerBean("testClock", Clock.class, () -> clock, bean -> bean.setPrimary(true));
            context.register(OutboxRelayConfiguration.class);

            // when
            context.refresh();

            // then
            var processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBeansOfType(OutboxRelay.class)).hasSize(1);
            assertThat(processor.getScheduledTasks()).hasSize(1);
            verify(taskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), eq(firstExecution), eq(Duration.ofSeconds(1)));
            verifyNoInteractions(dataSource, transactionManager, snsClient, destinationResolver);
        }
    }
}
