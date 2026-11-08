package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventMessageDecoder;
import com.backtoback.contest.exam.service.ProblemSnapshotService.Snapshot;

/** 공통 소비기와 소비 트랜잭션에서 문제 스냅샷의 순서와 작업 복구를 검증한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ProblemStateChangedHandlerTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 소비기와 저장소를 테스트 데이터베이스에 연결한다. */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private EventConsumer consumer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProblemSnapshotService snapshots;
    @Autowired
    private ProblemStateChangedHandler handler;
    @Autowired
    private EventMessageDecoder decoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 최신 회차와 언어를 보존하고 중복 및 오래된 이벤트를 무시한다. */
    @Test
    @DisplayName("문제의 최신 스냅샷은 중복과 역전 이벤트로 되돌아가지 않는다")
    void preservesNewerSnapshot() {
        // given
        var ack = mock(Runnable.class);
        var latest
            = message(
                UUID.randomUUID().toString(),
                99201,
                3,
                "GENERAL",
                "PUBLIC",
                "\"91\"",
                "4",
                "[\"java\",\"python3\"]",
                "null"
            );
        // when
        consumer.consume(latest, ack);
        consumer.consume(latest, ack);
        consumer
            .consume(
                message(UUID.randomUUID().toString(), 99201, 2, "GENERAL", "PRIVATE", "null", "null", "[]", "null"),
                ack
            );
        // then
        var row = row(99201);
        assertThat(((Number)row.get("source_version")).longValue()).isEqualTo(3);
        assertThat(row.get("source_status")).isEqualTo("PUBLIC");
        assertThat(((Number)row.get("source_revision_id")).longValue()).isEqualTo(91);
        assertThat(row.get("source_supported_languages").toString()).contains("java", "python3");
        assertThat(((Number)row.get("version")).longValue()).isEqualTo(1);
        verify(ack, org.mockito.Mockito.times(3)).run();
    }

    /** 동시 최초 이벤트도 같은 가드 행에 직렬화해 큰 버전을 보존한다. */
    @Test
    @DisplayName("동시에 도착한 문제 이벤트는 더 큰 원천 버전을 보존한다")
    void concurrentSnapshotsPreserveLatestVersion() throws Exception {
        // given
        var start = new CountDownLatch(1);
        var oldMessage
            = message(UUID.randomUUID().toString(), 99207, 1, "GENERAL", "PUBLIC", "\"97\"", "1", "[\"java\"]", "null");
        var newMessage
            = message(
                UUID.randomUUID().toString(),
                99207,
                2,
                "GENERAL",
                "PUBLIC",
                "\"97\"",
                "1",
                "[\"java\",\"cpp\"]",
                "null"
            );
        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            var older = executor.submit(() -> {
                start.await();
                consumer.consume(oldMessage, mock(Runnable.class));
                return null;
            });
            var newer = executor.submit(() -> {
                start.await();
                consumer.consume(newMessage, mock(Runnable.class));
                return null;
            });
            start.countDown();
            older.get(10, TimeUnit.SECONDS);
            newer.get(10, TimeUnit.SECONDS);
            // then
            assertThat(((Number)row(99207).get("source_version")).longValue()).isEqualTo(2);
            assertThat(row(99207).get("source_supported_languages").toString()).contains("java", "cpp");
        }
    }

    /**
     * 기존 일관된 읽기 스냅샷이 있어도 가드 잠금 뒤에는 선행 커밋의 최신 버전을 읽는다.
     *
     * @throws Exception 경합 스레드의 실행 또는 대기에 실패한 경우
     */
    @Test
    @DisplayName("이전 읽기 스냅샷을 가진 소비 트랜잭션도 잠금 대기 뒤 최신 문제 버전을 보존한다")
    void lockingReadIgnoresEarlierConsistentSnapshot() throws Exception {
        // given
        long problemId = 99211;
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    problemId,
                    1,
                    "GENERAL",
                    "PUBLIC",
                    "\"101\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        var newVersionSaved = new CountDownLatch(1);
        var snapshotRead = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var eventId = UUID.randomUUID().toString();
        var oldMessage = message(eventId, problemId, 2, "GENERAL", "PUBLIC", "\"101\"", "1", "[\"java\"]", "null");
        var transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                // when
                var writer = executor.submit(() -> transactions.executeWithoutResult(status -> {
                    snapshots
                        .apply(
                            new Snapshot(
                                problemId,
                                3,
                                "GENERAL",
                                "PUBLIC",
                                101L,
                                1,
                                java.util.List.of("java", "cpp"),
                                null
                            )
                        );
                    newVersionSaved.countDown();
                    await(releaseWriter);
                }));
                assertThat(newVersionSaved.await(10, TimeUnit.SECONDS)).isTrue();
                var reader = executor.submit(() -> transactions.executeWithoutResult(status -> {
                    jdbc
                        .update(
                            "INSERT INTO processed_events (event_id, event_type, processed_at, created_at) "
                                + "VALUES (?, 'ProblemStateChanged', NOW(6), NOW(6))",
                            eventId
                        );
                    long observed
                        = jdbc
                            .queryForObject(
                                "SELECT source_version FROM contest_resource_guards "
                                    + "WHERE resource_type='PROBLEM' AND resource_id=?",
                                Long.class,
                                problemId
                            );
                    assertThat(observed).isEqualTo(1);
                    snapshotRead.countDown();
                    handler.handle(decoder.decode(oldMessage));
                }));
                assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reader.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseWriter.countDown();
                writer.get(10, TimeUnit.SECONDS);
                reader.get(10, TimeUnit.SECONDS);
                // then
                assertThat(((Number)row(problemId).get("source_version")).longValue()).isEqualTo(3);
                assertThat(row(problemId).get("source_supported_languages").toString()).contains("java", "cpp");
                assertThat(
                    jdbc
                        .queryForObject(
                            "SELECT COUNT(*) FROM processed_events WHERE event_id=?",
                            Integer.class,
                            eventId
                        )
                ).isEqualTo(1);
            } finally {
                releaseWriter.countDown();
            }
        }
    }

    /**
     * 테스트 트랜잭션의 대기 실패를 호출 스레드에 전달한다.
     *
     * @param latch 선행 트랜잭션을 해제할 신호
     * @throws IllegalStateException 신호를 받기 전 대기가 중단되거나 제한 시간을 넘긴 경우
     */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** 다른 작업은 준비 상태를 유지하고 일치한 이전 작업 이벤트는 최신 공개 상태를 보존한다. */
    @Test
    @DisplayName("다른 작업 이벤트는 준비를 유지하고 같은 작업 완료는 최신 공개 상태로 복구한다")
    void recoversOnlyMatchingOperation() {
        // given
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99202,
                    2,
                    "GENERAL",
                    "PUBLIC",
                    "\"92\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        jdbc
            .update(
                "UPDATE contest_resource_guards SET state='PREPARED', operation_result='PENDING', "
                    + "operation_type='PROBLEM_HIDE', operation_id='current', expected_source_version=2 "
                    + "WHERE resource_type='PROBLEM' AND resource_id=99202"
            );
        // when
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99202,
                    4,
                    "GENERAL",
                    "PRIVATE",
                    "\"92\"",
                    "1",
                    "[\"java\"]",
                    "\"other\""
                ),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99202).get("state")).isEqualTo("PREPARED");
        // when
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99202,
                    5,
                    "GENERAL",
                    "PUBLIC",
                    "\"92\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99202).get("state")).isEqualTo("PREPARED");
        // when
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99202,
                    3,
                    "GENERAL",
                    "PRIVATE",
                    "\"92\"",
                    "1",
                    "[\"java\"]",
                    "\"current\""
                ),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99202).get("state")).isEqualTo("OPEN");
        assertThat(row(99202).get("operation_result")).isEqualTo("COMMITTED");
        assertThat(((Number)row(99202).get("source_version")).longValue()).isEqualTo(5);
    }

    /** 회차가 없는 스냅샷은 빈 배열을 저장하고 잘못된 이벤트는 소비 기록과 ACK를 남기지 않는다. */
    @Test
    @DisplayName("회차 없음과 빈 언어 목록을 저장하고 잘못된 공개 상태는 롤백한다")
    void emptyRevisionAndInvalidPublic() {
        // when
        consumer
            .consume(
                message(UUID.randomUUID().toString(), 99203, 1, "GENERAL", "PRIVATE", "null", "null", "[]", "null"),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99203).get("source_revision_id")).isNull();
        assertThat(row(99203).get("source_supported_languages").toString()).isEqualTo("[]");
        // given
        var eventId = UUID.randomUUID().toString();
        var ack = mock(Runnable.class);
        // when & then
        assertThatThrownBy(
            () -> consumer.consume(message(eventId, 99203, 2, "GENERAL", "PUBLIC", "null", "null", "[]", "null"), ack)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId)
        ).isZero();
        assertThat(((Number)row(99203).get("source_version")).longValue()).isEqualTo(1);
        verify(ack, never()).run();
    }

    /** 보관 상태는 더 큰 공개 버전으로 되살리지 않는다. */
    @Test
    @DisplayName("보관된 문제의 후속 공개 이벤트는 롤백하고 차단을 유지한다")
    void archivedIsTerminal() {
        // given
        consumer
            .consume(
                message(UUID.randomUUID().toString(), 99204, 1, "GENERAL", "ARCHIVED", "null", "null", "[]", "null"),
                mock(Runnable.class)
            );
        var eventId = UUID.randomUUID().toString();
        // when & then
        assertThatThrownBy(
            () -> consumer
                .consume(
                    message(eventId, 99204, 2, "GENERAL", "PUBLIC", "\"94\"", "1", "[\"java\"]", "null"),
                    mock(Runnable.class)
                )
        ).isInstanceOf(IllegalStateException.class);
        assertThat(row(99204).get("state")).isEqualTo("BLOCKED");
        assertThat(row(99204).get("source_status")).isEqualTo("ARCHIVED");
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?", Integer.class, eventId)
        ).isZero();
    }

    /** 완료된 일반 문제 가드는 더 최신 공개 이벤트로만 해제한다. */
    @Test
    @DisplayName("동일 작업의 비공개 완료 후 더 최신 공개 이벤트가 가드를 해제한다")
    void newerPublicReopensCompletedGuard() {
        // given
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99206,
                    1,
                    "GENERAL",
                    "PUBLIC",
                    "\"96\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        jdbc
            .update(
                "UPDATE contest_resource_guards SET state='PREPARED', operation_result='PENDING', "
                    + "operation_type='PROBLEM_HIDE', operation_id='hide', expected_source_version=1 "
                    + "WHERE resource_type='PROBLEM' AND resource_id=99206"
            );
        // when
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99206,
                    2,
                    "GENERAL",
                    "PRIVATE",
                    "\"96\"",
                    "1",
                    "[\"java\"]",
                    "\"hide\""
                ),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99206).get("state")).isEqualTo("BLOCKED");
        // when
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99206,
                    3,
                    "GENERAL",
                    "PUBLIC",
                    "\"96\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99206).get("state")).isEqualTo("OPEN");
        assertThat(row(99206).get("operation_result")).isEqualTo("COMMITTED");
    }

    /** 시험 전용 문제의 비공개 상태는 대회 사용을 위한 가드를 닫지 않는다. */
    @Test
    @DisplayName("시험 전용 비공개 문제의 사용 회차 이벤트는 열린 가드를 유지한다")
    void privateExamOnlySnapshotKeepsGuardOpen() {
        // given
        var event
            = message(
                UUID.randomUUID().toString(),
                99208,
                1,
                "EXAM_ONLY",
                "PRIVATE",
                "\"98\"",
                "1",
                "[\"java\"]",
                "null"
            );
        // when
        consumer.consume(event, mock(Runnable.class));
        // then
        assertThat(row(99208).get("state")).isEqualTo("OPEN");
        assertThat(row(99208).get("source_scope")).isEqualTo("EXAM_ONLY");
    }

    /** 취소로 기존 차단 상태를 복원한 작업도 이후 공개 상태를 반영한다. */
    @Test
    @DisplayName("차단 상태에서 준비한 작업을 취소한 뒤 최신 일반 공개 이벤트가 가드를 해제한다")
    void newerPublicReopensAfterAbortedOperation() {
        // given
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99209,
                    1,
                    "GENERAL",
                    "PRIVATE",
                    "\"99\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        jdbc
            .update(
                "UPDATE contest_resource_guards SET state='BLOCKED', operation_result='ABORTED', "
                    + "operation_type='PROBLEM_HIDE', operation_id='archive' WHERE resource_type='PROBLEM' "
                    + "AND resource_id=99209"
            );
        // when
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99209,
                    2,
                    "GENERAL",
                    "PUBLIC",
                    "\"99\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        // then
        assertThat(row(99209).get("state")).isEqualTo("OPEN");
        assertThat(row(99209).get("operation_result")).isEqualTo("ABORTED");
    }

    /** 생성 때 정한 문제 용도는 이후 이벤트가 바꿀 수 없다. */
    @Test
    @DisplayName("원천 용도가 바뀐 이벤트는 롤백하고 기존 스냅샷을 보존한다")
    void rejectsScopeChange() {
        // given
        consumer
            .consume(
                message(
                    UUID.randomUUID().toString(),
                    99210,
                    1,
                    "EXAM_ONLY",
                    "PRIVATE",
                    "\"100\"",
                    "1",
                    "[\"java\"]",
                    "null"
                ),
                mock(Runnable.class)
            );
        var eventId = UUID.randomUUID().toString();
        // when & then
        assertThatThrownBy(
            () -> consumer
                .consume(
                    message(eventId, 99210, 2, "GENERAL", "PUBLIC", "\"100\"", "1", "[\"java\"]", "null"),
                    mock(Runnable.class)
                )
        ).isInstanceOf(IllegalStateException.class);
        assertThat(row(99210).get("source_scope")).isEqualTo("EXAM_ONLY");
        assertThat(((Number)row(99210).get("source_version")).longValue()).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?", Integer.class, eventId)
        ).isZero();
    }

    /** 시험에 고정된 회차는 최신 스냅샷 갱신과 별도로 보존한다. */
    @Test
    @DisplayName("문제의 최신 회차가 바뀌어도 시험에 고정된 회차는 유지한다")
    void preservesPinnedExamRevision() {
        // given
        long examId = 99205;
        jdbc
            .update(
                "INSERT INTO exams (id, study_id, creator_id, title, mode, starts_at, ends_at, status, "
                    + "exam_revision, total_score, result_revision, version, created_at, updated_at) "
                    + "VALUES (?, 1, 1, '고정 회차', 'FIXED', NOW(6), NOW(6), 'SCHEDULED', 1, 100, 0, 0, NOW(6), NOW(6))",
                examId
            );
        try {
            jdbc
                .update(
                    "INSERT INTO exam_problems (exam_id, problem_id, problem_revision_id, problem_title, "
                        + "revision_number, score, display_order, created_at, updated_at) "
                        + "VALUES (?, 99205, 50, '고정 문제', 1, 100, 1, NOW(6), NOW(6))",
                    examId
                );
            // when
            consumer
                .consume(
                    message(
                        UUID.randomUUID().toString(),
                        99205,
                        2,
                        "GENERAL",
                        "PUBLIC",
                        "\"51\"",
                        "2",
                        "[\"java\"]",
                        "null"
                    ),
                    mock(Runnable.class)
                );
            // then
            var pinned
                = jdbc
                    .queryForMap(
                        "SELECT problem_revision_id, revision_number FROM exam_problems "
                            + "WHERE exam_id=? AND problem_id=99205",
                        examId
                    );
            assertThat(((Number)pinned.get("problem_revision_id")).longValue()).isEqualTo(50);
            assertThat(((Number)pinned.get("revision_number")).intValue()).isEqualTo(1);
            assertThat(((Number)row(99205).get("source_revision_id")).longValue()).isEqualTo(51);
        } finally {
            jdbc.update("DELETE FROM exam_problems WHERE exam_id=?", examId);
            jdbc.update("DELETE FROM exams WHERE id=?", examId);
        }
    }

    /** 문제 가드의 현재 상태를 읽는다. */
    private java.util.Map<String, Object> row(long id) {
        return jdbc
            .queryForMap("SELECT * FROM contest_resource_guards WHERE resource_type='PROBLEM' AND resource_id=?", id);
    }

    /** 필요한 필드만 달리해 v2 이벤트 JSON을 만든다. */
    private String message(
        String eventId,
        long id,
        long version,
        String scope,
        String visibility,
        String revision,
        String revisionNumber,
        String languages,
        String operationId
    ) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ProblemStateChanged\",\"schemaVersion\":\"v2\","
            + "\"occurredAt\":\"2026-11-04T09:00:00.000000+09:00\",\"aggregateId\":\"" + id
            + "\",\"correlationId\":\"test\",\"problemId\":\"" + id + "\",\"sourceVersion\":" + version
            + ",\"scope\":\"" + scope + "\",\"visibility\":\"" + visibility + "\",\"problemRevisionId\":" + revision
            + ",\"revisionNumber\":" + revisionNumber + ",\"supportedLanguages\":" + languages
            + ",\"changeType\":\"VISIBILITY\",\"changedAt\":\"2026-11-04T09:00:00+09:00\",\"operationId\":"
            + operationId + "}";
    }
}
