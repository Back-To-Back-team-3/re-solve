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
import com.backtoback.common.exception.CommonErrorCode;
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
class ProblemHideServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final AtomicLong IDS = new AtomicLong(9000);
    @Autowired
    private ProblemHideService service;
    @Autowired
    private ProblemOperationService operations;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OutboxRelay relay;
    @MockitoSpyBean
    private OutboxEventWriter outbox;
    @MockitoSpyBean
    private ProblemHideStore store;
    @Autowired
    private ObjectMapper mapper;
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
            VALUES(?,?,'GENERAL','PUBLIC',1,now(6),now(6))
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

    /** 완료 전송 장애에도 같은 최초 응답과 한 번의 상태 변경을 보존한다. */
    @Test
    @DisplayName("완료 전송 실패와 재시도는 상태·감사·Outbox를 중복시키지 않는다")
    void committedReplay() {
        org.mockito.Mockito
            .doThrow(new org.springframework.web.client.ResourceAccessException("offline"))
            .when(guard)
            .finish(any());
        var first = hide("same", false);
        var replay = hide("same", false);
        assertThat(first.status()).isEqualTo(200);
        assertThat(mapper.readTree(first.json())).isEqualTo(mapper.readTree(replay.json()));
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isEqualTo(2);
        assertThat(count("audit_logs", "target_id")).isOne();
        assertThat(count("outbox_events", "aggregate_id")).isOne();
        org.mockito.Mockito.verify(guard).prepare(any());
    }

    /** 불명확한 준비는 원래 작업을 보존하고 이후 재시도에 연결한다. */
    @Test
    @DisplayName("준비 결과 불명은 PENDING을 유지하고 다른 상태 작업을 차단한다")
    void unknownPreparation() {
        org.mockito.Mockito
            .when(guard.prepare(any()))
            .thenThrow(new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE))
            .thenReturn(null);
        assertThatThrownBy(() -> hide("same", false)).isInstanceOf(ProblemException.class);
        assertThat(
            jdbc.queryForObject("SELECT state FROM problem_state_operations WHERE problem_id=?", String.class, id)
        ).isEqualTo("PENDING");
        assertThatThrownBy(() -> hide("other", true)).isInstanceOf(ProblemException.class);
        assertThat(hide("same", false).status()).isEqualTo(200);
    }

    /** 준비 뒤 버전 변경을 재검증하고 같은 준비만 취소한다. */
    @Test
    @DisplayName("준비 이후 버전 경합은 ABORTED로 보존하고 취소를 전달한다")
    void versionRace() {
        org.mockito.Mockito.when(guard.prepare(any())).thenAnswer(call -> {
            assertThat(
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            ).isFalse();
            jdbc.update("UPDATE problems SET row_version=2 WHERE id=?", id);
            return null;
        });
        assertThat(hide("same", false).status()).isEqualTo(409);
        assertThat(hide("same", false).status()).isEqualTo(409);
        assertThat(count("outbox_events", "aggregate_id")).isZero();
        org.mockito.Mockito.verify(guard).finish(any());
        org.mockito.Mockito.verify(guard).prepare(any());
    }

    /** Outbox 실패는 상태·감사·최초 응답을 모두 롤백한다. */
    @Test
    @DisplayName("Outbox 저장 실패는 PENDING을 남기고 상태 변경 전체를 롤백한다")
    void rollback() {
        doAnswer(call -> {
            throw new IllegalStateException("outbox unavailable");
        })
            .when(AopTestUtils.<OutboxEventWriter>getUltimateTargetObject(outbox))
            .append(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> hide("same", true)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, id))
            .isEqualTo("PUBLIC");
        assertThat(
            jdbc.queryForObject("SELECT state FROM problem_state_operations WHERE problem_id=?", String.class, id)
        ).isEqualTo("PENDING");
        assertThat(count("audit_logs", "target_id")).isZero();
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.never()).finish(any());
    }

    /** 같은 키의 동시 준비를 실제 DB에서 직렬화한다. */
    @Test
    @DisplayName("같은 키 동시 요청은 한 번만 보관하고 같은 응답을 반환한다")
    void concurrentReplay() throws Exception {
        var ready = new java.util.concurrent.CyclicBarrier(2);
        org.mockito.Mockito.when(guard.prepare(any())).thenAnswer(call -> {
            ready.await(10, TimeUnit.SECONDS);
            return null;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> hide("same", true));
            var right = pool.submit(() -> hide("same", true));
            var first = left.get(20, TimeUnit.SECONDS);
            assertThat(mapper.readTree(first.json()))
                .isEqualTo(mapper.readTree(right.get(20, TimeUnit.SECONDS).json()));
        }
        assertThat(count("outbox_events", "aggregate_id")).isOne();
        assertThat(jdbc.queryForObject("SELECT row_version FROM problems WHERE id=?", Integer.class, id)).isEqualTo(2);
    }

    /**
     * 원래 버전의 요청을 실행한다.
     *
     * @param key 멱등키
     * @param archive 보관 여부
     * @return 원래 응답
     */
    private ProblemPublicationService.Result hide(String key, boolean archive) {
        return service.hide(id, id, key, 1, archive, "reason", "hide-test", "127.0.0.1");
    }

    /**
     * 해당 문제의 결과 행 수를 조회한다.
     *
     * @param table 테스트 대상 테이블
     * @param column 문제 식별 열
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
