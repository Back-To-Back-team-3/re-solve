package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.OutboxRelay;

/** 실제 MySQL에서 일반 제출의 소비 기록과 집계의 원자성·동시성을 검증한다. */
@SpringBootTest(
    properties = {
        "problem.messaging.validation-queue-url=http://localhost:4566/000000000000/problem-validation-queue",
        "PROBLEM_QUEUE_URL=",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "problem.image-storage.bucket=test-bucket"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class PracticeResultHandlerTest {
    @Container
    private static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db").withEnv("MYSQL_ROOT_HOST", "%");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /**
     * 실행 중 컨테이너의 연결 주소를 제공한다.
     *
     * @param registry 속성 등록기
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    private Clock clock;
    @MockitoBean
    private OutboxRelay relay;

    /** 중복·역순·AC 진입과 이탈에서 제출 수와 최초 해결을 보존한다. */
    @Test
    @DisplayName("최신 판정만 갱신하고 최초 AC는 접수 시각 기준으로 보존한다")
    void duplicateAndReorderedAttempts() {
        var acks = new AtomicInteger();
        String first = message(101, 1, 1, 2, "WA", 2);
        consumer.consume(first, acks::incrementAndGet);
        consumer.consume(first, acks::incrementAndGet);
        consumer.consume(message(101, 1, 1, 2, "WA", 2), acks::incrementAndGet);
        consumer.consume(message(101, 1, 1, 1, "AC", 2), acks::incrementAndGet);
        assertThat(count("SELECT judge_attempt FROM problem_submission_results WHERE submission_id=101")).isEqualTo(2);
        assertThat(count("SELECT is_solved FROM member_problem_statuses WHERE problem_id=1")).isZero();
        consumer.consume(message(101, 1, 1, 3, "AC", 2), acks::incrementAndGet);
        consumer.consume(message(102, 1, 1, 1, "AC", 1), acks::incrementAndGet);
        assertThat(count("SELECT accepted_submission_count FROM problem_statistics WHERE problem_id=1")).isEqualTo(2);
        consumer.consume(message(102, 1, 1, 2, "CE", 1), acks::incrementAndGet);
        consumer.consume(message(101, 1, 1, 4, "RE", 2), acks::incrementAndGet);
        assertThat(count("SELECT accepted_submission_count FROM problem_statistics WHERE problem_id=1")).isZero();
        assertThat(count("SELECT submission_count FROM problem_statistics WHERE problem_id=1")).isEqualTo(2);
        assertThat(count("SELECT attempt_count FROM member_problem_statuses WHERE problem_id=1")).isEqualTo(2);
        assertThat(count("SELECT is_solved FROM member_problem_statuses WHERE problem_id=1")).isOne();
        assertThat(count("SELECT first_solved_submission_id FROM member_problem_statuses WHERE problem_id=1"))
            .isEqualTo(102);
        consumer.consume(message(103, 1, 1, 1, "AC", 3), acks::incrementAndGet);
        assertThat(count("SELECT first_solved_submission_id FROM member_problem_statuses WHERE problem_id=1"))
            .isEqualTo(102);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT first_solved_at FROM member_problem_statuses WHERE problem_id=1",
                    LocalDateTime.class
                )
        ).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0, 1));
        assertThat(acks.get()).isEqualTo(9);
    }

    /** 불변 정보가 다른 재전달은 소비 이력과 선행 집계 생성을 함께 롤백한다. */
    @Test
    @DisplayName("제출의 회원·문제·접수 시각이 달라지면 낮은 회차도 ACK하지 않는다")
    void conflictingIdentity() {
        var acks = new AtomicInteger();
        consumer.consume(message(201, 2, 2, 2, "WA", 1), acks::incrementAndGet);
        for (String conflict : List
            .of(message(201, 3, 2, 1, "AC", 1), message(201, 2, 3, 1, "AC", 1), message(201, 2, 2, 1, "AC", 2))) {
            assertThatThrownBy(() -> consumer.consume(conflict, acks::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(acks.get()).isOne();
        assertThat(count("SELECT COUNT(*) FROM problem_statistics WHERE problem_id=3")).isZero();
        assertThat(count("SELECT COUNT(*) FROM member_problem_statuses WHERE problem_id=2 AND member_id=3")).isZero();
    }

    /** 최종 집계 쓰기가 실패하면 전체 소비가 롤백되고 같은 이벤트로 재처리할 수 있다. */
    @Test
    @DisplayName("회원 집계 쓰기 실패 후 같은 이벤트를 다시 처리한다")
    void retryAfterRollback() {
        var acks = new AtomicInteger();
        String body = message(401, 4, 4, 1, "AC", 1);
        jdbc.execute("""
            ALTER TABLE member_problem_statuses ADD CONSTRAINT test_reject_first_attempt
            CHECK (member_id <> 4 OR attempt_count = 0)
            """);
        try {
            assertThatThrownBy(() -> consumer.consume(body, acks::incrementAndGet))
                .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("ALTER TABLE member_problem_statuses DROP CHECK test_reject_first_attempt");
        }
        assertThat(acks.get()).isZero();
        assertThat(count("SELECT COUNT(*) FROM problem_submission_results WHERE submission_id=401")).isZero();
        assertThat(count("SELECT COUNT(*) FROM problem_statistics WHERE problem_id=4")).isZero();
        consumer.consume(body, acks::incrementAndGet);
        assertThat(count("SELECT accepted_submission_count FROM problem_statistics WHERE problem_id=4")).isOne();
    }

    /**
     * 최초 통계 생성 경합을 관측하고 경합 실패 메시지만 재전달한다.
     *
     * @throws Exception DB 잠금 관찰·소비 작업 실패
     */
    @Test
    @DisplayName("같은 문제에 두 회원이 동시에 처음 제출하면 두 제출 모두 한 번씩 집계한다")
    void concurrentSubmissions() throws Exception {
        LocalDateTime beforeUnlock;
        var acks = new AtomicInteger();
        try (
            var executor = Executors.newFixedThreadPool(2);
            var blocker = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = blocker.createStatement()
        ) {
            blocker.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO problem_statistics VALUES (5,0,0,NOW(6))");
            var tasks
                = List
                    .of(message(501, 5, 5, 1, "AC", 1), message(502, 6, 5, 1, "WA", 2))
                    .stream()
                    .map(body -> executor.submit(() -> {
                        try {
                            consumer.consume(body, acks::incrementAndGet);
                            return "";
                        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
                            return body;
                        }
                    }))
                    .toList();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting;
                do {
                    try (var rows = statement.executeQuery("""
                        SELECT COUNT(DISTINCT thread_id) FROM performance_schema.data_locks
                        WHERE object_schema=DATABASE() AND object_name='problem_statistics' AND index_name='PRIMARY'
                        AND lock_status='WAITING' AND lock_data='5'
                        """)) {
                        rows.next();
                        waiting = rows.getInt(1);
                    }
                    if (waiting < 2) {
                        Thread.sleep(25);
                    }
                } while (waiting < 2 && System.nanoTime() < deadline);
                assertThat(waiting).isEqualTo(2);
                beforeUnlock = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            } finally {
                blocker.rollback();
            }
            for (var task : tasks) {
                String retry = task.get(10, TimeUnit.SECONDS);
                if (!retry.isEmpty()) {
                    consumer.consume(retry, acks::incrementAndGet);
                }
            }
        }
        assertThat(jdbc.queryForObject("""
            SELECT MIN(updated_at) FROM problem_submission_results WHERE problem_id=5
            """, LocalDateTime.class)).isAfterOrEqualTo(beforeUnlock);
        assertThat(acks.get()).isEqualTo(2);
        assertThat(count("SELECT submission_count FROM problem_statistics WHERE problem_id=5")).isEqualTo(2);
        assertThat(count("SELECT accepted_submission_count FROM problem_statistics WHERE problem_id=5")).isOne();
        assertThat(count("SELECT COUNT(*) FROM member_problem_statuses WHERE problem_id=5 AND attempt_count=1"))
            .isEqualTo(2);
    }

    /** 시험·대회·진단·시스템 실패는 일반 풀이 결과로 집계하지 않는다. */
    @Test
    @DisplayName("일반 풀이 종결 이외의 이벤트를 통계에서 제외한다")
    void excludesOtherContexts() {
        var acks = new AtomicInteger();
        for (String type : List
            .of("ExamSubmissionJudged", "ContestSubmissionJudged", "DiagnosisSubmissionJudged", "SubmissionFailed")) {
            consumer.consume(message(601, 6, 6, 1, "AC", 1).replace("SubmissionJudged", type), acks::incrementAndGet);
        }
        assertThat(acks.get()).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM problem_statistics WHERE problem_id=6")).isZero();
    }

    /**
     * 저장된 집계값을 조회한다.
     *
     * @param sql 정수 단일 행 조회
     * @return 조회 결과
     */
    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    /**
     * 고정된 접수 시각에 대응하는 일반 제출 종결 메시지를 만든다.
     *
     * @param submission 제출 ID
     * @param member 회원 ID
     * @param problem 문제 ID
     * @param attempt 채점 회차
     * @param verdict 최종 판정
     * @param second 접수 시각 초
     * @return SQS 이벤트 원문
     */
    private String message(int submission, int member, int problem, int attempt, String verdict, int second) {
        return """
            {"eventId":"%s","eventType":"SubmissionJudged","schemaVersion":"v1",
            "occurredAt":"2026-10-01T00:00:00.000000+09:00","aggregateId":"%d","correlationId":"trace",
            "submissionId":"%d","userId":"%d","problemId":"%d","judgeAttempt":%d,"verdict":"%s",
            "submittedAt":"2026-10-01T00:00:%02d.000000+09:00"}
            """.formatted(UUID.randomUUID(), submission, submission, member, problem, attempt, verdict, second);
    }
}
