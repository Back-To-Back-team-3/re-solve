package com.backtoback.problem.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;

import com.backtoback.common.scheduling.service.EventRecordCleanupService;
import com.backtoback.problem.global.config.SchedulingConfig;

/**
 * SchedulingConfig이 problem-service DB에 정리 스케줄러를 올바르게 연결하는지 확인한다.
 * 공통 모듈의 경계 조건 테스트를 반복하지 않고, 서비스 수준 연결과 보관기간 경계만 검증한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventCleanupIntegrationTest {

    @Container
    private static final MySQLContainer DATABASE
        = new MySQLContainer("mysql:8.4").withUrlParam("connectTimeout", "5000").withUrlParam("socketTimeout", "10000");

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-11T09:00:00.000000");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private AnnotationConfigApplicationContext context;
    private EventRecordCleanupService service;
    private LockProvider lockProvider;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS processed_events");
        jdbcTemplate.execute("DROP TABLE IF EXISTS outbox_events");
        jdbcTemplate.execute("DROP TABLE IF EXISTS shedlock");
        new ResourceDatabasePopulator(new ClassPathResource("db/event-cleanup-test-schema.sql")).execute(dataSource);
        openContext();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("7일 경과 PUBLISHED 행을 삭제하고 경계·PENDING·FAILED는 보존한다")
    void deletesExpiredPublishedAndPreservesOthers() {
        // given
        var cutoff = NOW.minusDays(7);
        saveOutbox(1, "PUBLISHED", cutoff.minusNanos(1000));
        saveOutbox(2, "PUBLISHED", cutoff);
        saveOutbox(3, "PENDING", cutoff.minusDays(1));
        saveOutbox(4, "FAILED", cutoff.minusDays(1));

        // when
        var deleted = service.cleanPublishedEvents();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM outbox_events ORDER BY id", Long.class))
            .containsExactly(2L, 3L, 4L);
    }

    @Test
    @DisplayName("14일 경과 소비 기록을 삭제하고 경계·최근 행은 보존한다")
    void deletesExpiredProcessedEventsAndPreservesBoundary() {
        // given
        var cutoff = NOW.minusDays(14);
        saveProcessed(1, cutoff.minusNanos(1000), NOW);
        saveProcessed(2, cutoff, NOW.minusDays(30));
        saveProcessed(3, cutoff.plusNanos(1000), NOW);

        // when
        var deleted = service.cleanProcessedEvents();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM processed_events ORDER BY id", Long.class))
            .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("SchedulingConfig이 LockProvider를 등록해 두 컨텍스트가 같은 잠금을 동시에 획득할 수 없다")
    void preventsSimultaneousLockAcquisitionAcrossInstances() {
        // given
        var configuration
            = new LockConfiguration(
                Instant.parse("2100-01-01T00:00:00Z"),
                "EventCleanupLockProbe",
                Duration.ofMinutes(30),
                Duration.ZERO
            );
        try (var second = newContext()) {
            var secondProvider = second.getBean(LockProvider.class);

            // when
            var acquired = lockProvider.lock(configuration);

            // then
            assertThat(acquired).isPresent();
            var lock = acquired.orElseThrow();
            try {
                assertThat(secondProvider.lock(configuration)).isEmpty();
            } finally {
                lock.unlock();
            }

            // when: 잠금 해제 후 다른 인스턴스 획득 가능
            var reacquired = secondProvider.lock(configuration);
            assertThat(reacquired).isPresent();
            reacquired.orElseThrow().unlock();
        }
    }

    private void openContext() {
        context = newContext();
        service = context.getBean(EventRecordCleanupService.class);
        lockProvider = context.getBean(LockProvider.class);
    }

    private AnnotationConfigApplicationContext newContext() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(DataSource.class, () -> dataSource);
        ctx.registerBean(PlatformTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
        ctx
            .registerBean(
                "testClock",
                Clock.class,
                () -> Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC),
                bean -> bean.setPrimary(true)
            );
        ctx.register(SchedulingConfig.class);
        ctx.refresh();
        return ctx;
    }

    private void saveOutbox(long id, String status, LocalDateTime publishedAt) {
        var createdAt = Timestamp.valueOf(NOW.minusDays(40));
        jdbcTemplate
            .update(
                """
                    INSERT INTO outbox_events (
                        id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
                        correlation_id, payload, status, retry_count,
                        occurred_at, published_at, created_at, updated_at
                    ) VALUES (?, ?, 'ProblemStateChanged', 'v1', 'PROBLEM', '1', 'cleanup-test', '{}', ?, 0, ?, ?, ?, ?)
                    """,
                id,
                UUID.randomUUID().toString(),
                status,
                createdAt,
                publishedAt == null ? null : Timestamp.valueOf(publishedAt),
                createdAt,
                createdAt
            );
    }

    private void saveProcessed(long id, LocalDateTime processedAt, LocalDateTime createdAt) {
        jdbcTemplate.update("""
            INSERT INTO processed_events (id, event_id, event_type, processed_at, created_at)
            VALUES (?, ?, 'ProblemStateChanged', ?, ?)
            """, id, UUID.randomUUID().toString(), Timestamp.valueOf(processedAt), Timestamp.valueOf(createdAt));
    }
}
