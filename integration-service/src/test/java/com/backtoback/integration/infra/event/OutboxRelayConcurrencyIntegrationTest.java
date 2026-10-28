package com.backtoback.integration.infra.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.integration.infra.event.domain.OutboxEvent;
import com.backtoback.integration.infra.event.domain.OutboxEventStatus;
import com.backtoback.integration.infra.event.publisher.EventPublisher;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;
import com.backtoback.integration.infra.event.service.OutboxRelayService;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayConcurrencyIntegrationTest {

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

    @MockitoBean
    private EventPublisher eventPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("동시에 두 스레드가 SKIP LOCKED로 선점 시 중복 없이 서로 다른 이벤트를 선점한다")
    void concurrencySkipLockedExcludesAlreadyLockedRows() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> events = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            events
                .add(
                    OutboxEvent
                        .builder()
                        .eventId("concurrent-evt-" + i)
                        .eventType("SubmissionJudged")
                        .schemaVersion("v1")
                        .aggregateType("SUBMISSION")
                        .aggregateId(String.valueOf(1000 + i))
                        .correlationId("corr-" + i)
                        .payload("{\"test\":" + i + "}")
                        .occurredAt(now)
                        .build()
                );
        }
        outboxEventRepository.saveAll(events);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<List<Long>> threadSelectedIds = Collections.synchronizedList(new ArrayList<>());
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    txTemplate.execute(status -> {
                        List<OutboxEvent> locked = outboxEventRepository.findPendingEventsForUpdateSkipLocked(5);
                        List<Long> ids = locked.stream().map(OutboxEvent::getId).toList();
                        threadSelectedIds.add(ids);
                        // 선점 락을 유지하기 위해 잠시 대기
                        try {
                            TimeUnit.MILLISECONDS.sleep(300);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Exception exception) {
                    exception.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(threadSelectedIds).hasSize(2);

        List<Long> listA = threadSelectedIds.get(0);
        List<Long> listB = threadSelectedIds.get(1);

        // 두 스레드가 선점한 ID 목록에 교집합이 없어야 함 (SKIP LOCKED)
        assertThat(listA).doesNotContainAnyElementsOf(listB);
    }

    @Test
    @DisplayName("OutboxRelayService 실행 시 DB의 PENDING 이벤트가 PUBLISHED 상태로 정상 갱신된다")
    void relayPendingEventsUpdatesDbStatus() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event
            = OutboxEvent
                .builder()
                .eventId("integration-relay-evt")
                .eventType("SubmissionJudged")
                .schemaVersion("v1")
                .aggregateType("SUBMISSION")
                .aggregateId("9999")
                .correlationId("c-int")
                .payload("{\"test\":\"value\"}")
                .occurredAt(now)
                .build();
        outboxEventRepository.save(event);

        int processed = outboxRelayService.relayPendingEvents();
        assertThat(processed).isPositive();

        OutboxEvent updated = outboxEventRepository.findByEventId("integration-relay-evt").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();
    }
}
