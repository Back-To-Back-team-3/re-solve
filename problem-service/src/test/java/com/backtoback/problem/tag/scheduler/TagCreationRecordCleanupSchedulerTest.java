package com.backtoback.problem.tag.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.global.config.SchedulingConfig;
import com.backtoback.problem.tag.service.TagCreationRecordService;

class TagCreationRecordCleanupSchedulerTest {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    /**
     * 두 애플리케이션 인스턴스가 공유할 DB에 실제 마이그레이션을 적용한다.
     */
    @BeforeAll
    static void startInfrastructure() {
        Assumptions
            .assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 ShedLock 통합 테스트를 건너뜁니다.");
        try {
            MYSQL.start();
            Flyway
                .configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
        } catch (RuntimeException | Error startupFailure) {
            try {
                stopInfrastructure();
            } catch (RuntimeException | Error cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
    }

    /** 두 스케줄러 인스턴스가 공유한 테스트 DB 컨테이너를 종료한다. */
    @AfterAll
    static void stopInfrastructure() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    /**
     * 같은 DB를 사용하는 다른 인스턴스의 중복 실행을 건너뛰고 잠금 해제 후 실행하는지 검증한다.
     *
     * @throws Exception 동시 실행 대기 또는 결과 조회에 실패한 경우
     */
    @Test
    @DisplayName("한 인스턴스가 정리 중이면 다른 인스턴스는 건너뛰고 해제 후 실행한다")
    void skipsConcurrentExecutionAndRunsAfterUnlock() throws Exception {
        // given
        var firstService = mock(TagCreationRecordService.class);
        var secondService = mock(TagCreationRecordService.class);
        when(firstService.findExpiredIdsForCleanup()).thenReturn(List.of(1L));
        when(secondService.findExpiredIdsForCleanup()).thenReturn(List.of(1L));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(firstService.deleteExpiredById(1L)).thenAnswer(invocation -> {
            started.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("첫 정리 작업의 종료 신호를 받지 못했습니다.");
            }
            return true;
        });
        try (
            var first = createContext(firstService, true, Clock.systemUTC());
            var second = createContext(secondService, true, Clock.systemUTC());
            var executor = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            // when
            var running
                = executor.submit(() -> first.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords());
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                second.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();
                verify(secondService, never()).findExpiredIdsForCleanup();
            } finally {
                finish.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
            second.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();

            // then
            verify(firstService).deleteExpiredById(1L);
            verify(secondService).deleteExpiredById(1L);
        }
    }

    /**
     * 정리 중 예외가 발생해도 잠금을 해제하여 다른 인스턴스가 다음 실행을 수행하는지 검증한다.
     */
    @Test
    @DisplayName("정리 작업이 실패해도 ShedLock을 해제한다")
    void releasesLockAfterFailure() {
        // given
        var firstService = mock(TagCreationRecordService.class);
        var secondService = mock(TagCreationRecordService.class);
        when(firstService.findExpiredIdsForCleanup()).thenReturn(List.of(1L));
        when(secondService.findExpiredIdsForCleanup()).thenReturn(List.of(1L));
        when(firstService.deleteExpiredById(1L)).thenThrow(new IllegalStateException("정리 실패"));
        try (
            var first = createContext(firstService, true, Clock.systemUTC());
            var second = createContext(secondService, true, Clock.systemUTC())
        ) {
            // when & then
            assertThatThrownBy(() -> first.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정리 실패");
            second.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();
            verify(secondService).deleteExpiredById(1L);
        }
    }

    /**
     * 정리 비활성화 설정이면 스케줄러를 등록하지 않는지 검증한다.
     */
    @Test
    @DisplayName("만료 정리를 비활성화하면 스케줄러 빈을 등록하지 않는다")
    void doesNotRegisterDisabledScheduler() {
        // given
        var service = mock(TagCreationRecordService.class);
        try (var context = createContext(service, false, Clock.systemUTC())) {
            // when
            var schedulers = context.getBeansOfType(TagCreationRecordCleanupScheduler.class);

            // then
            assertThat(schedulers).isEmpty();
            verify(service, never()).findExpiredIdsForCleanup();
        }
    }

    /**
     * 한 후보의 DB 오류는 다음 후보 처리를 막지 않는지 검증한다.
     */
    @Test
    @DisplayName("개별 후보 처리 오류가 나도 나머지 후보를 처리한다")
    void continuesAfterOneCandidateFails() {
        // given
        var service = mock(TagCreationRecordService.class);
        when(service.findExpiredIdsForCleanup()).thenReturn(List.of(1L, 2L, 3L));
        when(service.deleteExpiredById(1L)).thenThrow(new DataIntegrityViolationException("개별 행 오류"));
        when(service.deleteExpiredById(2L)).thenReturn(true);
        when(service.deleteExpiredById(3L)).thenReturn(false);
        try (var context = createContext(service, true, Clock.systemUTC())) {
            // when
            context.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();

            // then
            verify(service).deleteExpiredById(1L);
            verify(service).deleteExpiredById(2L);
            verify(service).deleteExpiredById(3L);
        }
    }

    /**
     * DB 연결 장애는 회차를 중단하고 뒤의 후보를 시작하지 않는지 검증한다.
     */
    @Test
    @DisplayName("DB 연결 장애가 나면 정리 회차를 중단한다")
    void stopsRunAfterDatabaseConnectionFailure() {
        // given
        var service = mock(TagCreationRecordService.class);
        when(service.findExpiredIdsForCleanup()).thenReturn(List.of(1L, 2L));
        when(service.deleteExpiredById(1L)).thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));
        try (var context = createContext(service, true, Clock.systemUTC())) {
            // when & then
            assertThatThrownBy(() -> context.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords())
                .isInstanceOf(DataAccessResourceFailureException.class);
            verify(service).deleteExpiredById(1L);
            verify(service, never()).deleteExpiredById(2L);
        }
    }

    /**
     * 20초가 지나면 진행 중 처리를 기다린 뒤 새 후보를 시작하지 않는지 검증한다.
     */
    @Test
    @DisplayName("20초가 지나면 새 후보 처리를 시작하지 않는다")
    void stopsStartingCandidatesAfterTwentySeconds() {
        // given
        var service = mock(TagCreationRecordService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
        when(service.findExpiredIdsForCleanup()).thenReturn(List.of(1L, 2L));
        when(service.deleteExpiredById(1L)).thenAnswer(invocation -> {
            clock.advanceSeconds(20);
            return true;
        });
        try (var context = createContext(service, true, clock)) {
            // when
            context.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();

            // then
            verify(service).deleteExpiredById(1L);
            verify(service, never()).deleteExpiredById(2L);
        }
    }

    /**
     * 시계가 뒤로 움직인 구간을 처리 시간에 더하지 않는지 검증한다.
     */
    @Test
    @DisplayName("시계 역행은 처리 시간에 더하지 않는다")
    void ignoresBackwardClockMovementWhenMeasuringRunTime() {
        // given
        var service = mock(TagCreationRecordService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
        when(service.findExpiredIdsForCleanup()).thenReturn(List.of(1L, 2L, 3L));
        when(service.deleteExpiredById(1L)).thenAnswer(invocation -> {
            clock.advanceSeconds(-5);
            return true;
        });
        when(service.deleteExpiredById(2L)).thenAnswer(invocation -> {
            clock.advanceSeconds(25);
            return true;
        });
        try (var context = createContext(service, true, clock)) {
            // when
            context.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();

            // then
            verify(service).deleteExpiredById(1L);
            verify(service).deleteExpiredById(2L);
            verify(service, never()).deleteExpiredById(3L);
        }
    }

    /**
     * 실제 잠금 설정과 독립된 JDBC 연결을 사용하는 스케줄러 인스턴스를 생성한다.
     *
     * @param service 실행 여부를 검증할 정리 서비스
     * @param enabled 정리 스케줄러 활성화 여부
     * @param clock 정리 시간 한도 검증에 사용할 시계
     * @return 호출자가 종료해야 하는 애플리케이션 컨텍스트
     */
    private AnnotationConfigApplicationContext createContext(
        TagCreationRecordService service,
        boolean enabled,
        Clock clock
    ) {
        var context = new AnnotationConfigApplicationContext();
        if (!enabled) {
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource("cleanup-test", Map.of("problem.tag-idempotency.cleanup.enabled", "false"))
                );
        }
        context
            .registerBean(
                JdbcTemplate.class,
                () -> new JdbcTemplate(
                    new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                )
            );
        context.registerBean(TagCreationRecordService.class, () -> service);
        context.registerBean(Clock.class, () -> clock);
        context.register(SchedulingConfig.class, TagCreationRecordCleanupScheduler.class);
        context.refresh();
        return context;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant initialInstant) {
            this(initialInstant, ZoneId.of("Asia/Seoul"));
        }

        private MutableClock(Instant initialInstant, ZoneId zone) {
            instant = new AtomicReference<>(initialInstant);
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advanceSeconds(long seconds) {
            instant.updateAndGet(current -> current.plusSeconds(seconds));
        }
    }
}
