package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.request.ProblemPublicationRequest;
import com.backtoback.problem.problem.dto.response.ProblemOperationResponse;

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
class ProblemPublicationServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final AtomicLong IDS = new AtomicLong(9000);
    @Autowired
    private ProblemPublicationService service;
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
            VALUES(?,?,'GENERAL','PRIVATE',1,now(6),now(6))
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

    /** 동일 요청은 현재 상태·버전이 바뀌어도 최초 응답과 원래 작업 조회를 보존한다. */
    @Test
    @DisplayName("공개와 감사·Outbox를 한 번 저장하고 변경 뒤에도 최초 결과를 반환한다")
    void publicationAndReplay() {
        var result = publish("key", 1);
        assertThat(result.status()).isEqualTo(200);
        assertThat(mapper.readTree(result.json()).path("data").path("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(mapper.readTree(result.json()).path("data").path("rowVersion").asInt()).isEqualTo(2);
        assertThat(count("audit_logs", "target_id")).isOne();
        assertThat(count("outbox_events", "aggregate_id")).isOne();
        assertThat(
            jdbc.queryForObject("SELECT published_at FROM problems WHERE id=?", java.time.LocalDateTime.class, id)
        ).isNotNull();
        var operationId = operationId("key");
        jdbc.update("UPDATE problems SET visibility='PRIVATE',row_version=3 WHERE id=?", id);
        var replay = publish("key", 1);
        assertThat(replay.status()).isEqualTo(result.status());
        assertThat(mapper.readTree(replay.json())).isEqualTo(mapper.readTree(result.json()));
        assertThat(ProblemOperationResponse.from(operations.find(id, operationId)))
            .isEqualTo(new ProblemOperationResponse(operationId, "COMMITTED", "2", "PUBLIC"));
        assertThatThrownBy(() -> publish("key", 3))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
            );
        assertThatThrownBy(() -> operations.find(id + 10000, operationId))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );
        var payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    Long.toString(id)
                );
        assertThat(payload).contains("VISIBILITY", "PUBLIC", "cpp", "publication-correlation");
    }

    /** 확정 실패는 같은 키에서 다시 실행하지 않고 작업 당시의 오류와 상태를 보존한다. */
    @Test
    @DisplayName("버전 충돌은 ABORTED로 보존하고 나중에 버전이 맞아도 재실행하지 않는다")
    void abortedReplay() {
        var rejected = publish("rejected", 2);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(mapper.readTree(rejected.json()).path("error").path("code").asString())
            .isEqualTo("PROBLEM_VERSION_CONFLICT");
        jdbc.update("UPDATE problems SET row_version=2 WHERE id=?", id);
        var replay = publish("rejected", 2);
        assertThat(replay.status()).isEqualTo(rejected.status());
        assertThat(mapper.readTree(replay.json())).isEqualTo(mapper.readTree(rejected.json()));
        var operationId = operationId("rejected");
        assertThat(ProblemOperationResponse.from(operations.find(id, operationId)))
            .isEqualTo(new ProblemOperationResponse(operationId, "ABORTED", "2", "PRIVATE"));
        assertThat(mapper.readTree(publish("new-key", 2).json()).path("data").path("rowVersion").asInt()).isEqualTo(3);
    }

    /** 작성 권한·통과 회차·미종결 작업을 공개 전에 검사한다. */
    @Test
    @DisplayName("작성자 이외 요청과 미통과 회차·미종결 작업은 공개하지 않는다")
    void rejectsUnauthorizedOrUnavailable() {
        assertThatThrownBy(
            () -> service.publish(id, id + 1, "key", new ProblemPublicationRequest(1), "publication-correlation", null)
        )
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
        jdbc.update("UPDATE problem_validations SET result_status='REJECTED' WHERE problem_revision_id=?", id);
        var rejected = publish("missing", 1);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(mapper.readTree(rejected.json()).path("error").path("code").asString())
            .isEqualTo("PROBLEM_INVALID_STATE_TRANSITION");
        jdbc
            .update(
                "UPDATE problem_state_operations SET state='PENDING',response_status=NULL,response_json=NULL"
                    + " WHERE problem_id=?",
                id
            );
        assertThatThrownBy(() -> publish("other", 1))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_VERSION_CONFLICT)
            );
        assertThat(count("outbox_events", "aggregate_id")).isZero();
    }

    /**
     * 통과 회차가 있어도 공개할 수 없는 범위·상태를 거절한다.
     *
     * @param scope 문제 범위
     * @param visibility 현재 공개 상태
     */
    @ParameterizedTest
    @CsvSource(
        {
            "EXAM_ONLY,PRIVATE",
            "GENERAL,PUBLIC",
            "GENERAL,ARCHIVED"
        }
    )
    @DisplayName("시험 전용·이미 공개·보관 문제는 공개하지 않는다")
    void rejectsInvalidState(String scope, String visibility) {
        jdbc.update("UPDATE problems SET scope=?,visibility=? WHERE id=?", scope, visibility, id);
        var rejected = publish("invalid-state", 1);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(mapper.readTree(rejected.json()).path("error").path("code").asString())
            .isEqualTo("PROBLEM_INVALID_STATE_TRANSITION");
        assertThat(count("outbox_events", "aggregate_id")).isZero();
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isOne();
    }

    /**
     * 실제 문제 행 잠금에 대기한 같은 키의 동시 요청이 하나의 작업으로 종결되는지 확인한다.
     *
     * @throws Exception DB 연결 또는 동시 실행 실패
     */
    @Test
    @DisplayName("동일 키의 동시 공개는 잠금 뒤 한 번만 반영된다")
    void concurrentPublication() throws Exception {
        try (
            var blocker = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var executor = Executors.newFixedThreadPool(2)
        ) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("SELECT id FROM problems WHERE id=" + id + " FOR UPDATE").close();
            }
            var first = executor.submit(() -> publish("same", 1));
            var second = executor.submit(() -> publish("same", 1));
            try {
                // DB 잠금이 해제되기 전에는 어느 요청도 상태를 확정할 수 없다.
                assertThatThrownBy(() -> first.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(second.isDone()).isFalse();
            } finally {
                blocker.commit();
            }
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.status()).isEqualTo(secondResult.status());
            assertThat(mapper.readTree(firstResult.json())).isEqualTo(mapper.readTree(secondResult.json()));
        }
        assertThat(count("problem_state_operations", "problem_id")).isOne();
        assertThat(count("outbox_events", "aggregate_id")).isOne();
        assertThat(count("audit_logs", "target_id")).isOne();
    }

    /** Outbox 저장 실패는 공개·버전·작업 저장을 함께 롤백한다. */
    @Test
    @DisplayName("Outbox 저장이 실패하면 공개와 작업·감사를 모두 롤백한다")
    void rollback() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("outbox unavailable");
        })
            .when(AopTestUtils.<OutboxEventWriter>getUltimateTargetObject(outbox))
            .append(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> publish("failed", 1)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, id))
            .isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isOne();
        assertThat(count("problem_state_operations", "problem_id")).isZero();
        assertThat(count("audit_logs", "target_id")).isZero();
        assertThat(count("outbox_events", "aggregate_id")).isZero();
    }

    /** 모든 변경이 DB에 반영된 뒤의 예외도 같은 트랜잭션을 롤백하는지 확인한다. */
    @Test
    @DisplayName("공개·작업·감사·Outbox 저장 후 실패하면 모든 변경을 롤백한다")
    void rollbackAfterAllWrites() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // saveAndFlush까지 완료한 동일 트랜잭션에서 실제 저장을 확인한 뒤 실패시킨다.
            assertThat(count("problem_state_operations", "problem_id")).isOne();
            assertThat(count("audit_logs", "target_id")).isOne();
            assertThat(count("outbox_events", "aggregate_id")).isOne();
            assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, id))
                .isEqualTo("PUBLIC");
            assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id))
                .isEqualTo(2);
            throw new IllegalStateException("failure before commit");
        }).when(AopTestUtils.<ProblemPublicationStore>getUltimateTargetObject(store)).publish(any());
        assertThatThrownBy(() -> publish("failed-after-writes", 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("failure before commit");
        assertThat(count("problem_state_operations", "problem_id")).isZero();
        assertThat(count("audit_logs", "target_id")).isZero();
        assertThat(count("outbox_events", "aggregate_id")).isZero();
        assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, id))
            .isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isOne();
        assertThat(
            jdbc.queryForObject("SELECT published_at FROM problems WHERE id=?", java.time.LocalDateTime.class, id)
        ).isNull();
    }

    /**
     * 이전 배포에서 저장한 메시지·상세·HTTP 상태를 현재 코드로 재구성하지 않는다.
     *
     * @param version 성공 또는 확정 실패를 만들 기대 버전
     * @param status 저장된 최초 상태
     * @param json 저장된 최초 공통 응답
     */
    @ParameterizedTest
    @CsvSource(
        value = {
            "1|200|{\"success\":true,\"message\":\"이전 공개 안내\",\"data\":{\"visibility\":\"PUBLIC\",\"rowVersion\":2}}",
            "2|422|{\"success\":false,\"error\":{\"code\":\"PROBLEM_VERSION_CONFLICT\","
                + "\"message\":\"이전 오류 안내\",\"details\":[{\"field\":\"rowVersion\",\"reason\":\"기존 상세\"}]}}"
        },
        delimiter = '|'
    )
    @DisplayName("종결 응답은 저장된 HTTP 상태·메시지·상세까지 보존한다")
    void replaysStoredEnvelope(int version, int status, String json) {
        publish("historic", version);
        jdbc
            .update(
                "UPDATE problem_state_operations SET response_status=?,response_json=? WHERE problem_id=?",
                status,
                json,
                id
            );
        var replay = publish("historic", version);
        assertThat(replay.status()).isEqualTo(status);
        assertThat(mapper.readTree(replay.json())).isEqualTo(mapper.readTree(json));
    }

    /**
     * 고정 관리자·추적 정보로 공개를 요청한다.
     *
     * @param key 멱등키
     * @param version 기대 버전
     * @return 공개 결과
     */
    private ProblemPublicationService.Result publish(String key, int version) {
        return service.publish(id, id, key, new ProblemPublicationRequest(version), "publication-correlation", null);
    }

    /**
     * 현재 문제의 지정 키에 대응하는 작업 ID를 읽는다.
     *
     * @param key 원래 멱등키
     * @return 생성된 작업 ID
     */
    private String operationId(String key) {
        return jdbc
            .queryForObject(
                "SELECT operation_id FROM problem_state_operations WHERE problem_id=? AND idempotency_key=?",
                String.class,
                id,
                key
            );
    }

    /**
     * 현재 문제와 연결된 부수 효과의 개수를 조회한다.
     *
     * @param table 테스트가 지정한 테이블
     * @param column 문제 식별자 컬럼
     * @return 연결된 행 수
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
