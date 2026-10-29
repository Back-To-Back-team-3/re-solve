package com.backtoback.common.scheduling.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 실제 MySQL과 독립 Spring 컨텍스트로 명시적 등록·DB 시각·서비스 인스턴스 간 잠금 경합을 검증한다.
 * 예약 실행기를 등록하지 않고 메서드를 직접 호출해 잠금 프록시의 실행 경계만 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchedulerLockConfigurationTest {
    @Container
    private static final MySQLContainer DATABASE
        = new MySQLContainer("mysql:8.4")
            // 컨테이너 준비 중 응답 없는 연결이 테스트를 무기한 대기시키지 않도록 한다.
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "10000");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    /**
     * 잠금 공급자가 테이블을 생성하는 것으로 오인하지 않도록 서비스 역할의 스키마를 먼저 준비한다.
     */
    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS shedlock");
        new ResourceDatabasePopulator(new ClassPathResource("db/scheduling-test-schema.sql")).execute(dataSource);
    }

    /**
     * 라이브러리 의존성만 있는 컨텍스트가 잠금이나 예약 실행을 자동 등록하지 않는지 확인한다.
     */
    @Test
    @DisplayName("공통 설정을 등록하지 않으면 잠금 공급자와 예약 작업이 활성화되지 않는다")
    void doesNotActivateFromDependencyAlone() {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);

            // when
            context.refresh();

            // then
            assertThat(context.getBeansOfType(LockProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        }
    }

    /**
     * 잠금 설정의 명시적 등록이 작업 예약이나 Outbox 기능의 활성화까지 확장되지 않는지 확인한다.
     */
    @Test
    @DisplayName("잠금 설정을 등록하면 공급자 한 개만 구성하고 예약 실행은 켜지 않는다")
    void activatesOnlyExplicitLockConfiguration() {
        // when
        try (var context = openContext(null)) {
            // then
            assertThat(context.getBeansOfType(LockProvider.class)).hasSize(1);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shedlock", Integer.class)).isZero();
        }
    }

    /**
     * 미래 애플리케이션 시각을 전달해도 DB 시각으로 만료를 계산하고 해제 후 다른 공급자가 획득하는지 확인한다.
     */
    @Test
    @DisplayName("서로 다른 공급자는 같은 작업을 동시에 잠글 수 없고 DB 시각으로 만료를 계산한다")
    void usesDatabaseTimeAndReleasesLockForAnotherProvider() {
        // given
        var configuration
            = new LockConfiguration(
                Instant.parse("2100-01-01T00:00:00Z"),
                "DatabaseClockProbe",
                Duration.ofMinutes(30),
                Duration.ZERO
            );
        try (var first = openContext(null); var second = openContext(null)) {
            var firstProvider = first.getBean(LockProvider.class);
            var secondProvider = second.getBean(LockProvider.class);

            // when
            var acquired = firstProvider.lock(configuration);

            // then
            assertThat(acquired).isPresent();
            var firstLock = acquired.orElseThrow();
            try {
                assertThat(secondProvider.lock(configuration)).isEmpty();
                assertThat(jdbcTemplate.queryForObject("""
                    SELECT lock_until > CURRENT_TIMESTAMP(3)
                        AND lock_until <= CURRENT_TIMESTAMP(3) + INTERVAL 31 MINUTE
                    FROM shedlock WHERE name = 'DatabaseClockProbe'
                    """, Boolean.class)).isTrue();
                assertThat(jdbcTemplate.queryForObject("SELECT locked_by FROM shedlock", String.class)).isNotBlank();
            } finally {
                firstLock.unlock();
            }

            // when
            var reacquired = secondProvider.lock(configuration);

            // then
            assertThat(reacquired).isPresent();
            reacquired.orElseThrow().unlock();
        }
    }

    /**
     * 작업이 잠금을 가진 동안 다른 서비스 인스턴스의 같은 메서드 본문이 실행되지 않는지 확인한다.
     *
     * @throws Exception 작업 스레드 동기화 또는 제한 시간 내 종료에 실패할 때
     */
    @Test
    @DisplayName("같은 작업을 두 컨텍스트에서 호출하면 잠금을 가진 인스턴스만 실행한다")
    void skipsConcurrentInvocationAndAllowsExecutionAfterRelease() throws Exception {
        // given
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstRuns = new AtomicInteger();
        var secondRuns = new AtomicInteger();
        try (var first = openContext(() -> {
            firstRuns.incrementAndGet();
            entered.countDown();
            awaitRelease(release);
        }); var second = openContext(secondRuns::incrementAndGet); var executor = Executors.newSingleThreadExecutor()) {
            var firstJob = first.getBean(LockProbeJob.class);
            var secondJob = second.getBean(LockProbeJob.class);
            var running = executor.submit(firstJob::execute);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

                // when
                secondJob.execute();

                // then
                assertThat(firstRuns.get()).isEqualTo(1);
                assertThat(secondRuns.get()).isZero();
            } finally {
                release.countDown();
            }
            running.get(10, TimeUnit.SECONDS);

            // when
            secondJob.execute();

            // then
            assertThat(secondRuns.get()).isEqualTo(1);
        }
    }

    /**
     * 작업 예외가 호출자에게 전파되면서도 잠금이 해제돼 다른 인스턴스가 실행할 수 있는지 확인한다.
     */
    @Test
    @DisplayName("잠금을 가진 작업이 실패해도 예외를 전파하고 다음 인스턴스가 실행할 수 있다")
    void releasesLockAfterJobFailure() {
        // given
        var failure = new IllegalStateException("simulated scheduling failure");
        var recoveredRuns = new AtomicInteger();
        try (var failing = openContext(() -> {
            throw failure;
        }); var recovered = openContext(recoveredRuns::incrementAndGet)) {
            // when & then
            assertThatThrownBy(() -> failing.getBean(LockProbeJob.class).execute()).isSameAs(failure);

            // when
            recovered.getBean(LockProbeJob.class).execute();

            // then
            assertThat(recoveredRuns.get()).isEqualTo(1);
        }
    }

    /**
     * 기존 서비스 잠금 설정을 교체하지 않은 상태가 공급자 두 개로 기동하지 않는지 확인한다.
     */
    @Test
    @DisplayName("기존 LockProvider가 함께 등록되면 기존 설정 교체를 안내하고 시작을 거절한다")
    void rejectsDuplicateLockProviderConfiguration() {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean("existingLockProvider", LockProvider.class, () -> mock(LockProvider.class));
            context.register(SchedulerLockConfiguration.class);

            // when & then
            assertThatThrownBy(context::refresh)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one LockProvider")
                .hasMessageContaining("replace the existing lock configuration");
        }
    }

    /**
     * 서비스 인스턴스마다 독립적인 컨텍스트와 작업 프록시를 만들되 같은 서비스 DB를 공유한다.
     *
     * @param action 잠금 획득 후 실행할 테스트 업무, 작업 Bean 없이 공급자만 검증할 때는 null
     * @return 명시적 잠금 설정과 작업 Bean을 등록한 열린 컨텍스트
     */
    private AnnotationConfigApplicationContext openContext(Runnable action) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        if (action != null) {
            context.registerBean(LockProbeJob.class, () -> new LockProbeJob(action));
        }
        context.register(SchedulerLockConfiguration.class);
        context.refresh();
        return context;
    }

    /**
     * 경합 확인이 끝날 때까지 작업의 잠금을 유지하며 실패 시에도 대기 시간을 제한한다.
     *
     * @param release 테스트 본문이 작업 종료를 허용할 신호
     */
    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the scheduler test lock");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scheduler test was interrupted", failure);
        }
    }

    /**
     * 서비스가 등록한 메서드에서 실제 ShedLock 프록시가 잠금을 유지한 채 업무를 실행하는지 확인한다.
     */
    public static class LockProbeJob {
        private final Runnable action;

        /**
         * 잠금 획득 후 호출할 테스트 업무를 연결한다.
         *
         * @param action 현재 인스턴스의 테스트 업무
         */
        public LockProbeJob(Runnable action) {
            this.action = action;
        }

        /**
         * 수동 호출에도 실제 잠금이 적용되는지 확인한 뒤 테스트 업무를 실행한다.
         */
        @SchedulerLock(
            name = "CommonSchedulingLockProbe",
            lockAtMostFor = "PT30M"
        )
        public void execute() {
            LockAssert.assertLocked();
            action.run();
        }
    }
}
