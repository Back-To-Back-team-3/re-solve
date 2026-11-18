package com.backtoback.study.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.scheduling.scheduler.OutboxCleanupScheduler;
import com.backtoback.common.scheduling.scheduler.ProcessedEventCleanupScheduler;
import com.backtoback.common.time.ClockConfiguration;

/**
 * 스터디 서비스에 등록한 공통 이벤트 기록 정리가 스터디 스키마와 공통 잠금으로 동작하는지 확인한다
 * (스케줄러 명세서 §0.2.2·§0.2.3).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StudyEventCleanupIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private OutboxCleanupScheduler outboxCleanupScheduler;

    @Autowired
    private ProcessedEventCleanupScheduler processedEventCleanupScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("발행 완료 후 7일이 지난 기록만 지우고 7일 이내 발행 기록과 PENDING·FAILED는 남기며 잠금을 기록한다")
    void cleansOnlyOldPublishedEvents() {
        // given
        LocalDateTime now = now();
        String oldPublished = insertOutbox("PUBLISHED", now.minusDays(8));
        String recentPublished = insertOutbox("PUBLISHED", now.minusDays(6));
        // 발행 시각이 오래돼도 PUBLISHED가 아니면 보존 대상이다.
        String pending = insertOutbox("PENDING", now.minusDays(8));
        String failed = insertOutbox("FAILED", now.minusDays(8));

        // when
        outboxCleanupScheduler.cleanup();

        // then
        List<String> remaining = jdbcTemplate.queryForList("select event_id from outbox_events", String.class);
        assertThat(remaining).doesNotContain(oldPublished).contains(recentPublished, pending, failed);
        assertThat(lockCount("OutboxCleanupScheduler")).isEqualTo(1L);
    }

    @Test
    @DisplayName("소비 완료 후 14일이 지난 소비 기록만 지우고 14일 이내 기록은 남기며 잠금을 기록한다")
    void cleansOnlyOldProcessedEvents() {
        // given
        LocalDateTime now = now();
        String oldProcessed = insertProcessed(now.minusDays(15));
        String recentProcessed = insertProcessed(now.minusDays(13));

        // when
        processedEventCleanupScheduler.cleanup();

        // then
        List<String> remaining = jdbcTemplate.queryForList("select event_id from processed_events", String.class);
        assertThat(remaining).doesNotContain(oldProcessed).contains(recentProcessed);
        assertThat(lockCount("ProcessedEventCleanupScheduler")).isEqualTo(1L);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    private String insertOutbox(String status, LocalDateTime occurredAt) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime publishedAt = "PUBLISHED".equals(status) ? occurredAt : null;
        jdbcTemplate
            .update(
                "insert into outbox_events (event_id, event_type, schema_version, aggregate_type, aggregate_id, "
                    + "correlation_id, payload, status, retry_count, occurred_at, published_at, created_at, "
                    + "updated_at) values (?, 'StudyMemberJoined', 'v1', 'STUDY', '1', 'test', '{}', ?, 0, ?, ?, ?, ?)",
                eventId,
                status,
                occurredAt,
                publishedAt,
                occurredAt,
                occurredAt
            );
        return eventId;
    }

    private String insertProcessed(LocalDateTime processedAt) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate
            .update(
                "insert into processed_events (event_id, event_type, processed_at, created_at) "
                    + "values (?, 'SubmissionJudged', ?, ?)",
                eventId,
                processedAt,
                processedAt
            );
        return eventId;
    }

    private Long lockCount(String name) {
        return jdbcTemplate.queryForObject("select count(*) from shedlock where name = ?", Long.class, name);
    }
}
