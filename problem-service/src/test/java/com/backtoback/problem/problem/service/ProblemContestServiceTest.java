package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.problem.global.exception.ProblemException;

import tools.jackson.databind.ObjectMapper;

/** 실제 MySQL 잠금과 원자 저장으로 공개 멱등 계약을 검증한다. */
@SpringBootTest(
    properties = {
        "problem.messaging.validation-queue-url=http://localhost:4566/000000000000/problem-validation-queue",
        "PROBLEM_QUEUE_URL=",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ProblemContestServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final AtomicLong IDS = new AtomicLong(9000);
    @Autowired
    private ProblemContestService service;
    @Autowired
    private ProblemOperationService operations;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OutboxRelay relay;
    @MockitoSpyBean
    private OutboxEventWriter outbox;
    @MockitoSpyBean
    private ProblemPublicationStore store;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private com.backtoback.common.event.service.EventConsumer consumer;
    @Autowired
    private ProblemHideService hides;
    @MockitoBean
    private com.backtoback.problem.problem.client.ContestGuardClient guard;
    private long id;

    /**
     * 테스트 전용 DB·Redis 주소를 등록한다.
     *
     * @param registry 동적 속성 등록기
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** 통과한 사용 회차가 있는 비공개 일반 문제를 준비한다. */
    @BeforeEach
    void fixture() {
        id = IDS.incrementAndGet();
        jdbc.update("""
            INSERT INTO problems(id,author_id,scope,visibility,row_version,created_at,updated_at)
            VALUES(?,?,'EXAM_ONLY','PRIVATE',1,now(6),now(6))
            """, id, id);
        jdbc.update("""
            INSERT INTO problem_revisions(id,problem_id,revision_number,body_asset_id,title,difficulty,function_spec,
                time_limit_ms,memory_limit_kb,wall_time_limit_ms,output_limit_kb,author_id,idempotency_key,
                request_hash,response_row_version,created_at)
            SELECT ?,?,1,body_asset_id,title,difficulty,function_spec,time_limit_ms,memory_limit_kb,
                wall_time_limit_ms,output_limit_kb,?,'fixture',request_hash,1,now(6)
            FROM problem_revisions WHERE id=1
            """, id, id, id);
        jdbc.update("""
            INSERT INTO problem_code_assets(id,problem_id,owner_id,status,object_key,sha256,size_bytes,
                upload_expires_at,created_at,updated_at)
            VALUES(?,?,?,'LINKED',?,repeat('a',64),1,now(6),now(6),now(6))
            """, id, id, id, "publication-code-" + id);
        jdbc.update("""
            INSERT INTO problem_validations(problem_revision_id,requested_by,language,code_asset_id,
                trigger_type,request_hash,result_status,runtime_key,runner_version,created_at,updated_at)
            VALUES(?,?,'cpp',?,'MANUAL',repeat('a',64),'PASSED','runtime','v1',now(6),now(6))
            """, id, id, id);
    }

    /** 최초 연결을 보존하고 다른 대회·회차·관리자 덮어쓰기를 거절한다. */
    @Test
    @DisplayName("연결 재시도는 상태 변경 뒤에도 최초 버전을 보존한다")
    void immutableLink() {
        var linked = link(10);
        assertThat(linked.getContestLinkSourceVersion()).isEqualTo(2);
        jdbc.update("UPDATE problems SET row_version=4,visibility='ARCHIVED' WHERE id=?", id);
        assertThat(link(10).getContestLinkSourceVersion()).isEqualTo(2);
        assertThatThrownBy(() -> link(11)).isInstanceOf(ProblemException.class);
        assertThat(count("audit_logs", "target_id")).isOne();
    }

    /** 같은 연결의 병렬 요청을 실제 MySQL에서 직렬화한다. */
    @Test
    @DisplayName("같은 대회 연결의 동시 요청은 연결 버전과 감사를 한 번만 저장한다")
    void concurrentLink() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> link(10));
            var second = pool.submit(() -> link(10));
            assertThat(first.get(10, TimeUnit.SECONDS).getContestLinkSourceVersion()).isEqualTo(2);
            assertThat(second.get(10, TimeUnit.SECONDS).getContestLinkSourceVersion()).isEqualTo(2);
        }
        assertThat(count("audit_logs", "target_id")).isOne();
    }

    /** 링크 누락은 ACK하지 않고 연결 복구 후 같은 이벤트를 재처리한다. */
    @Test
    @DisplayName("연결 복구 뒤 같은 종료 이벤트를 반영하고 새 이벤트 ID 중복도 재공개하지 않는다")
    void endedReplay() {
        String message = message();
        var acks = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> consumer.consume(message, acks::incrementAndGet))
            .isInstanceOf(IllegalStateException.class);
        assertThat(acks.get()).isZero();
        link(10);
        consumer.consume(message, acks::incrementAndGet);
        consumer.consume(message, acks::incrementAndGet);
        consumer.consume(message(), acks::incrementAndGet);
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT scope FROM problems WHERE id=?", String.class, id))
            .isEqualTo("EXAM_ONLY");
        assertThat(count("outbox_events", "aggregate_id")).isOne();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT actor_id FROM audit_logs WHERE target_id=? " + "AND action='CONTEST_END_PUBLICATION'",
                    Long.class,
                    Long.toString(id)
                )
        ).isEqualTo(99L);
    }

    /** 검증 버전 증가는 허용하되 종료 뒤 관리자가 비공개로 바꾼 문제는 보호한다. */
    @Test
    @DisplayName("검증으로 증가한 버전은 공개하고 이후 관리자 비공개는 새 종료 이벤트에도 보존한다")
    void preservesAdminDecision() {
        link(10);
        jdbc.update("UPDATE problems SET row_version=3 WHERE id=?", id);
        consumer.consume(message(), () -> { /* ACK 성공을 모사한다. */ });
        assertThat(hides.hide(id, id, "hide", 4, false, "UNPUBLICATION", "trace", "127.0.0.1").status()).isEqualTo(200);
        consumer.consume(message(), () -> { /* ACK 성공을 모사한다. */ });
        assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, id))
            .isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isEqualTo(5);
    }

    /** 이벤트·공개·감사의 일부만 저장되지 않도록 한다. */
    @Test
    @DisplayName("종료 공개 Outbox 실패는 상태·감사·소비 기록을 함께 롤백한다")
    void rollback() {
        link(10);
        String message = message();
        doAnswer(call -> {
            throw new IllegalStateException("outbox unavailable");
        })
            .when(AopTestUtils.<OutboxEventWriter>getUltimateTargetObject(outbox))
            .append(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> consumer.consume(message, () -> { /* ACK 성공을 모사한다. */ }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, id))
            .isEqualTo("PRIVATE");
        assertThat(count("audit_logs", "target_id")).isOne();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM processed_events WHERE event_id=?",
                    Integer.class,
                    mapper.readTree(message).path("eventId").asString()
                )
        ).isZero();
    }

    /**
     * 작성자와 다른 정책 설정 관리자의 연결을 요청한다.
     *
     * @param contest 대회
     * @return 최초 연결
     */
    private com.backtoback.problem.problem.domain.Problem link(long contest) {
        return service
            .link(
                id,
                new com.backtoback.problem.problem.dto.request.ContestProblemLinkRequest(
                    Long.toString(contest),
                    Long.toString(id),
                    1,
                    "99"
                ),
                "127.0.0.1"
            );
    }

    /**
     * 고정 종료 이벤트를 구성한다.
     *
     * @return 이벤트 JSON
     */
    private String message() {
        return """
            {"eventId":"%s","eventType":"ContestEnded","schemaVersion":"v1",
             "occurredAt":"2026-10-01T00:00:00.000000+09:00","aggregateId":"10","correlationId":"trace",
             "contestId":"10","contestRevision":2,"endedAt":"2026-10-01T00:00:00+09:00",
             "isProblemsPublicAfterEnd":true,"problemIds":["%d"]}
            """.formatted(java.util.UUID.randomUUID(), id);
    }

    /**
     * 문제의 결과 수를 확인한다.
     *
     * @param table 테이블
     * @param column 식별 열
     * @return 행 수
     */
    private int count(String table, String column) {
        return jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class,
                Long.toString(id)
            );
    }
}
