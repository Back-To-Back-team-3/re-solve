package com.backtoback.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import com.backtoback.common.time.ClockConfiguration;

/**
 * member에 등록한 공통 Outbox 정리가 member 스키마(V5 outbox_events·V7 shedlock)에서 동작하는지 확인한다(스케줄러 §0.2.2).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxCleanupTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("member_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("resolve.auth.github.client-id", () -> "client-id");
        registry.add("resolve.auth.github.client-secret", () -> "client-secret");
        registry.add("resolve.auth.jwt.secret", () -> "test-jwt-secret-key-must-be-at-least-32-bytes");
    }

    @Autowired
    private OutboxCleanupScheduler outboxCleanupScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("발행 완료 후 7일이 지난 기록만 지우고, 7일 이내 발행 기록과 PENDING·FAILED는 남기며 ShedLock 잠금을 기록한다")
    void cleansOnlyOldPublishedEvents() {
        LocalDateTime now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        String oldPublished = insert("PUBLISHED", now.minusDays(8));
        String recentPublished = insert("PUBLISHED", now.minusDays(6));
        // 발행 시각이 오래돼도 PUBLISHED가 아니면 지우지 않는다(상태 조건 확인).
        String pending = insert("PENDING", now.minusDays(8));
        String failed = insert("FAILED", now.minusDays(8));

        outboxCleanupScheduler.cleanup();

        List<String> remaining = jdbcTemplate.queryForList("select event_id from outbox_events", String.class);
        assertThat(remaining).doesNotContain(oldPublished).contains(recentPublished, pending, failed);
        assertThat(
            jdbcTemplate
                .queryForObject("select count(*) from shedlock where name = 'OutboxCleanupScheduler'", Long.class)
        ).isEqualTo(1L);
    }

    private String insert(String status, LocalDateTime publishedAt) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt
            = publishedAt != null ? publishedAt : LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbcTemplate
            .update(
                "insert into outbox_events (event_id, event_type, schema_version, aggregate_type, aggregate_id, "
                    + "correlation_id, payload, status, retry_count, occurred_at, published_at, created_at, "
                    + "updated_at) values (?, 'MemberUpdated', 'v1', 'MEMBER', '1', 'test', '{}', ?, 0, ?, ?, ?, ?)",
                eventId,
                status,
                occurredAt,
                publishedAt,
                occurredAt,
                occurredAt
            );
        return eventId;
    }
}
