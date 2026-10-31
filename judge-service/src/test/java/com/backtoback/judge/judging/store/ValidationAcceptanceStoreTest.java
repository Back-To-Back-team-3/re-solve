package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.judging.config.JudgeEventStorageConfiguration;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.judging.service.ProblemValidationRequestedHandler;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL에서 검증 작업과 공통 소비·발행 기록의 원자성 및 경합을 검증한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        JudgeEventStorageConfiguration.class,
        ValidationAcceptanceStore.class,
        ProblemValidationRequestedHandler.class,
        ValidationAcceptanceStoreTest.TestBeans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ValidationAcceptanceStoreTest {
    private static final String HASH = "a".repeat(64);
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EventConsumer consumer;
    @MockitoSpyBean
    private ValidationAcceptanceStore store;
    @Autowired
    private OutboxEventWriter writer;
    @Autowired
    private PlatformTransactionManager manager;

    /**
     * 실제 업무 DB를 테스트 컨텍스트에 연결한다.
     *
     * @param registry 테스트 속성 등록기
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 테스트 간 작업·소비·발행 상태를 격리한다. */
    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM judge_validation_test_results");
        jdbc.update("DELETE FROM judge_validation_runs");
        jdbc.update("DELETE FROM judge_validation_jobs");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM outbox_events");
    }

    /** ACK 시점에 별도 트랜잭션에서도 작업을 조회할 수 있는지 검증한다. */
    @Test
    @DisplayName("검증 작업과 소비 기록을 커밋한 뒤 ACK한다")
    void commitsBeforeAcknowledgement() {
        var acknowledged = new AtomicBoolean();
        consumer.consume(message(UUID.randomUUID(), HASH), () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(count("judge_validation_jobs")).isEqualTo(1);
            assertThat(count("processed_events")).isEqualTo(1);
            acknowledged.set(true);
        });
        assertThat(acknowledged).isTrue();
        var job = jdbc.queryForMap("SELECT * FROM judge_validation_jobs");
        assertThat(job).containsEntry("status", "QUEUED").containsEntry("try_count", 0);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT TIMESTAMPDIFF(SECOND, created_at, deadline_at) FROM judge_validation_jobs",
                    Integer.class
                )
        ).isEqualTo(600);
        assertThat(count("judge_validation_runs")).isZero();
        assertThat(count("judge_validation_test_results")).isZero();
    }

    /** 삭제 실패 이후 같은 이벤트의 재전달이 작업을 다시 만들지 않는지 검증한다. */
    @Test
    @DisplayName("ACK 실패 후 재전달은 저장된 작업을 보존하고 삭제만 다시 시도한다")
    void retriesAcknowledgementWithoutRecreatingJob() {
        var event = message(UUID.randomUUID(), HASH);
        assertThatThrownBy(() -> consumer.consume(event, () -> {
            throw new IllegalStateException("ACK failure");
        })).isInstanceOf(IllegalStateException.class);
        var before = jdbc.queryForMap("SELECT * FROM judge_validation_jobs");
        consumer.consume(event, this::acknowledge);
        assertThat(jdbc.queryForMap("SELECT * FROM judge_validation_jobs")).isEqualTo(before);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    /** 도메인 중복 키의 다른 내용을 성공으로 처리하지 않는지 검증한다. */
    @Test
    @DisplayName("다른 eventId의 상충 요청은 소비 기록까지 롤백하고 ACK하지 않는다")
    void rejectsConflictingPayload() {
        consumer.consume(message(UUID.randomUUID(), HASH), this::acknowledge);
        var ack = new AtomicBoolean();
        assertThatThrownBy(() -> consumer.consume(message(UUID.randomUUID(), "b".repeat(64)), () -> ack.set(true)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ack).isFalse();
        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(count("judge_validation_jobs")).isEqualTo(1);
    }

    /** 커밋 직전 실패한 소비 작업의 모든 JDBC 변경이 롤백되는지 검증한다. */
    @Test
    @DisplayName("커밋 직전 실패하면 업무 저장과 Outbox를 함께 롤백한다")
    void rollsBackBeforeCommitFailure() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            store.accept(request(), "request-1");
            writer
                .append(
                    "VALIDATION",
                    "ProblemValidationCompleted",
                    "v1",
                    "2001",
                    "request-1",
                    Map.of("validationId", "2001", "status", "ERROR")
                );
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 커밋 직전 장애를 재현한다.
                 *
                 * @param readOnly 읽기 전용 여부
                 */
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit failure");
                }
            });
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("judge_validation_jobs")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    /** 업무 상태와 결과 이벤트를 같은 업무 트랜잭션에서 저장할 수 있는지 검증한다. */
    @Test
    @DisplayName("업무 종결과 결과 Outbox는 한 트랜잭션에서 커밋한다")
    void commitsResultAndOutboxTogether() {
        consumer.consume(message(UUID.randomUUID(), HASH), this::acknowledge);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc
                .update(
                    "UPDATE judge_validation_jobs SET status='ERROR', finished_at=deadline_at WHERE validation_id=2001"
                );
            writer
                .append(
                    "VALIDATION",
                    "ProblemValidationCompleted",
                    "v1",
                    "2001",
                    "request-1",
                    Map.of("validationId", "2001", "status", "ERROR")
                );
        });
        assertThat(jdbc.queryForObject("SELECT status FROM judge_validation_jobs", String.class)).isEqualTo("ERROR");
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThatThrownBy(
            () -> writer
                .append(
                    "VALIDATION",
                    "ProblemValidationCompleted",
                    "v1",
                    "2001",
                    "request-1",
                    Map.of("status", "ERROR")
                )
        ).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /**
     * 동일한 도메인 요청의 경합 승자만 작업을 만드는지 검증한다.
     *
     * @throws Exception 동시 요청 실행 또는 완료 대기가 실패한 경우
     */
    @Test
    @DisplayName("잠금 대기 후 같은 요청은 기존 작업을 재사용하고 두 이벤트를 ACK한다")
    void acceptsConcurrentEquivalentRequests() throws Exception {
        runConcurrently(HASH);
        assertThat(count("judge_validation_jobs")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(2);
    }

    /**
     * 상충하는 두 요청의 경합에서 패자의 소비 기록이 남지 않는지 검증한다.
     *
     * @throws Exception 동시 요청 실행 또는 완료 대기가 실패한 경우
     */
    @Test
    @DisplayName("잠금 대기 후 다른 요청 해시는 소비 기록을 롤백하고 ACK하지 않는다")
    void rejectsConcurrentConflictingRequests() throws Exception {
        assertThat(runConcurrently("b".repeat(64))).isEqualTo(1);
        assertThat(count("judge_validation_jobs")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    /**
     * 기존 스냅샷이 있어도 잠금 조회가 최신 작업을 대조하는지 검증한다.
     *
     * @throws Exception 동시 요청 실행 또는 완료 대기가 실패한 경우
     */
    @Test
    @DisplayName("상위 트랜잭션이 먼저 조회했어도 다른 요청의 선행 커밋을 현재 읽기로 대조한다")
    void checksCurrentRowAfterEarlierSnapshot() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(count("judge_validation_jobs")).isZero();
                try {
                    executor
                        .submit(() -> consumer.consume(message(UUID.randomUUID(), HASH), this::acknowledge))
                        .get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                store.accept(request(), "another-correlation");
            });
        }
        assertThat(count("judge_validation_jobs")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM judge_validation_jobs", String.class))
            .isEqualTo("request-1");
    }

    /** 저장 실패가 수신 완료 기록이나 ACK를 남기지 않는지 검증한다. */
    @Test
    @DisplayName("DB 작업 INSERT가 실패하면 소비 기록을 롤백하고 메시지를 삭제하지 않는다")
    void rollsBackReceiptWhenJobInsertFails() {
        jdbc.execute("""
            ALTER TABLE judge_validation_jobs ADD CONSTRAINT injected_storage_failure CHECK (validation_id <> 2001)
            """);
        var acknowledged = new AtomicBoolean();
        try {
            assertThatThrownBy(() -> consumer.consume(message(UUID.randomUUID(), HASH), () -> acknowledged.set(true)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("processed_events")).isZero();
            assertThat(count("judge_validation_jobs")).isZero();
            assertThat(acknowledged).isFalse();
        } finally {
            jdbc.execute("ALTER TABLE judge_validation_jobs DROP CHECK injected_storage_failure");
        }
    }

    /** 공통 소비 기록이 정리된 뒤에도 종결 작업을 되살리지 않는지 검증한다. */
    @Test
    @DisplayName("소비 기록 정리 후 새 eventId로 재수신해도 종결 상태와 기한을 보존한다")
    void preservesTerminalJobAfterReceiptCleanup() {
        consumer.consume(message(UUID.randomUUID(), HASH), this::acknowledge);
        jdbc
            .update(
                "UPDATE judge_validation_jobs SET status='ERROR', finished_at=deadline_at WHERE validation_id=2001"
            );
        jdbc.update("DELETE FROM processed_events");
        var before = jdbc.queryForMap("SELECT * FROM judge_validation_jobs");
        consumer.consume(message(UUID.randomUUID(), HASH), this::acknowledge);
        assertThat(jdbc.queryForMap("SELECT * FROM judge_validation_jobs")).isEqualTo(before);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    /**
     * 첫 요청의 미커밋 작업에 두 번째 UPSERT가 실제로 잠금 대기한 뒤 결과를 대조한다.
     *
     * @param secondHash 두 번째 요청 해시
     * @return 상충으로 거절된 요청 수
     * @throws Exception 실행 또는 DB 잠금 관측이 실패한 경우
     */
    private int runConcurrently(String secondHash) throws Exception {
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holdFirst = new AtomicBoolean(true);
        var firstAck = new AtomicBoolean();
        var secondAck = new AtomicBoolean();
        ValidationAcceptanceStore target = AopTestUtils.getUltimateTargetObject(store);
        doAnswer(invocation -> {
            boolean hold = holdFirst.compareAndSet(true, false);
            var result = invocation.callRealMethod();
            if (hold) {
                saved.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("첫 요청의 커밋 허용 신호를 받지 못했습니다.");
                }
            }
            return result;
        }).when(target).accept(any(ProblemValidationRequest.class), anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first
                    = executor
                        .submit(() -> consumer.consume(message(UUID.randomUUID(), HASH), () -> firstAck.set(true)));
                assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    try {
                        consumer.consume(message(UUID.randomUUID(), secondHash), () -> secondAck.set(true));
                        return 0;
                    } catch (IllegalArgumentException conflict) {
                        assertThat(conflict).hasMessage("같은 검증 ID의 요청 내용이 일치하지 않습니다.");
                        return 1;
                    }
                });
                awaitValidationLockWait();
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
                assertThat(firstAck).isFalse();
                assertThat(secondAck).isFalse();
                assertThat(count("judge_validation_jobs")).isZero();
                assertThat(count("processed_events")).isZero();
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                int conflicts = second.get(10, TimeUnit.SECONDS);
                assertThat(firstAck).isTrue();
                assertThat(secondAck.get()).isEqualTo(HASH.equals(secondHash));
                assertThat(jdbc.queryForObject("SELECT request_hash FROM judge_validation_jobs", String.class))
                    .isEqualTo(HASH);
                return conflicts;
            } finally {
                // 검증 실패 시에도 executor가 종료를 기다리기 전에 첫 트랜잭션을 해제한다.
                release.countDown();
            }
        }
    }

    /**
     * 격리된 테스트 DB의 PRIMARY 레코드 잠금 대기를 실제로 관측한다.
     * 관리자 연결은 performance_schema 읽기에만 사용하고 업무 계정 권한은 변경하지 않는다.
     */
    private void awaitValidationLockWait() {
        var monitor = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
            var waiting = monitor.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                    ON requested.ENGINE = waits.ENGINE
                    AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                WHERE requested.OBJECT_SCHEMA = ?
                    AND requested.OBJECT_NAME = 'judge_validation_jobs'
                    AND requested.INDEX_NAME = 'PRIMARY'
                    AND requested.LOCK_TYPE = 'RECORD'
                    AND requested.LOCK_DATA = '2001'
                    AND requested.LOCK_STATUS = 'WAITING'
                """, Integer.class, MYSQL.getDatabaseName());
            assertThat(waiting).isEqualTo(1);
        });
    }

    /**
     * 검증 테이블의 행 수를 조회한다.
     *
     * @param table 테스트가 고정한 테이블명
     * @return 행 수
     */
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * 고정 검증 요청을 생성한다.
     *
     * @return 불변 요청
     */
    private ProblemValidationRequest request() {
        return new ProblemValidationRequest(2001, 1001, "java", HASH);
    }

    /**
     * 수신 가능한 검증 이벤트를 생성한다.
     *
     * @param eventId 수신 이벤트 ID
     * @param hash 요청 해시
     * @return JSON 메시지
     */
    private String message(UUID eventId, String hash) {
        return """
            {"eventId":"%s","eventType":"ProblemValidationRequested","schemaVersion":"v1",
             "occurredAt":"2026-10-31T09:00:00.000000+09:00","aggregateId":"2001","correlationId":"request-1",
             "validationId":"2001","problemRevisionId":"1001","language":"java","requestHash":"%s"}
            """.formatted(eventId, hash);
    }

    /** DB 경계 검증에서 메시지 삭제가 성공한 상황을 나타낸다. */
    private void acknowledge() {
        // 삭제 실패 시나리오는 별도 테스트에서 예외로 재현한다.
    }

    /** 저장 시각과 JSON 처리를 테스트에 제공한다. */
    @TestConfiguration
    static class TestBeans {
        /**
         * 고정 저장 시각을 제공한다.
         *
         * @return 테스트 Clock
         */
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * 공통 이벤트 직렬화에 사용할 매퍼를 제공한다.
         *
         * @return JSON 매퍼
         */
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
