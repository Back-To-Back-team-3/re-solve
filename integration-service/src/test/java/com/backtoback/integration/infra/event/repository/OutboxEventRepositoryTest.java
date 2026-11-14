package com.backtoback.integration.infra.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.integration.infra.event.domain.OutboxEvent;
import com.backtoback.integration.infra.event.domain.ProcessedEvent;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class OutboxEventRepositoryTest {

    private static final String TEST_SECRET_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("integration_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("integration.crypto.secret-key", () -> TEST_SECRET_KEY_BASE64);
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    @DisplayName("PENDING 상태의 이벤트를 SKIP LOCKED 쿼리로 선점 조회할 수 있다")
    void findPendingEventsForUpdateSkipLocked() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event1
            = OutboxEvent
                .builder()
                .eventId("evt-1")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("100")
                .correlationId("c-1")
                .payload("{\"test\":1}")
                .occurredAt(now)
                .build();

        OutboxEvent event2
            = OutboxEvent
                .builder()
                .eventId("evt-2")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("101")
                .correlationId("c-2")
                .payload("{\"test\":2}")
                .occurredAt(now)
                .build();

        outboxEventRepository.saveAll(List.of(event1, event2));

        List<OutboxEvent> pending = outboxEventRepository.findPendingEventsForUpdateSkipLocked(10);

        assertThat(pending).hasSizeGreaterThanOrEqualTo(2);
        assertThat(pending).extracting(OutboxEvent::getEventId).contains("evt-1", "evt-2");
    }

    @Test
    @DisplayName("보관 기간이 지난 PUBLISHED 이벤트를 조회하고 일괄 삭제할 수 있다")
    void findAndCleanExpiredPublishedEvents() {
        LocalDateTime past = LocalDateTime.now().minusDays(8);
        OutboxEvent oldPublished
            = OutboxEvent
                .builder()
                .eventId("evt-old")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("200")
                .correlationId("c-old")
                .payload("{}")
                .occurredAt(past)
                .build();
        oldPublished.markPublished(past);
        outboxEventRepository.save(oldPublished);

        List<Long> expiredIds
            = outboxEventRepository.findExpiredPublishedEventIds(LocalDateTime.now().minusDays(7), 100);
        assertThat(expiredIds).contains(oldPublished.getId());

        int deletedCount = outboxEventRepository.deleteByIdIn(expiredIds);
        assertThat(deletedCount).isPositive();
        assertThat(outboxEventRepository.findById(oldPublished.getId())).isEmpty();
    }

    @Test
    @DisplayName("소비 완료된 이벤트의 존재 여부를 확인하고 만료 기록을 삭제할 수 있다")
    void processedEventExistsAndCleanup() {
        LocalDateTime past = LocalDateTime.now().minusDays(15);
        ProcessedEvent processed
            = ProcessedEvent
                .builder()
                .eventId("evt-proc-1")
                .eventType("SubmissionJudged")
                .processedAt(past)
                .createdAt(past)
                .build();
        processedEventRepository.save(processed);

        assertThat(processedEventRepository.existsByEventId("evt-proc-1")).isTrue();
        assertThat(processedEventRepository.existsByEventId("evt-none")).isFalse();

        List<Long> expiredIds
            = processedEventRepository.findExpiredProcessedEventIds(LocalDateTime.now().minusDays(14), 100);
        assertThat(expiredIds).contains(processed.getId());

        int deletedCount = processedEventRepository.deleteByIdIn(expiredIds);
        assertThat(deletedCount).isPositive();
        assertThat(processedEventRepository.findById(processed.getId())).isEmpty();
    }
}
