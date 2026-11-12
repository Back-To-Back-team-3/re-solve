package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.client.TestAssetClient;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.JudgeClient;
import com.backtoback.judge.judging.service.JudgeEngineCircuit;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.JudgeQueueMetrics;
import com.backtoback.judge.judging.service.SubmissionWorker;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL의 선점·임대와 종결/Outbox 원자성을 검증한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        OutboxStorageConfiguration.class,
        JudgeQueueMetrics.class,
        SubmissionExecutionStore.class,
        SubmissionExecutionStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionExecutionStoreTest {
    private static final Instant START = Instant.parse("2026-10-31T00:00:00Z");
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionExecutionStore store;
    @Autowired
    private Clock clock;
    @Autowired
    private JudgeQueueMetrics metrics;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void prepare() {
        given(clock.instant()).willReturn(START);
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_job_runs");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,idempotency_key,request_hash,
            language,source_code,code_size_bytes,status,judge_attempt,submitted_at,created_at,updated_at)
            VALUES (1,1,1,1,'PRACTICE','key',REPEAT('a',64),'java','x',1,'QUEUED',1,
            '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        jdbc.update("""
            INSERT INTO judge_jobs(id,submission_id,judge_attempt,status,try_count,unknown_retry_count,
                next_run_at,deadline_at,created_at,updated_at)
            VALUES (1,1,1,'PENDING',1,0,'2026-10-31 09:00:00','2026-10-31 09:10:00',
                '2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
            created_at,updated_at) VALUES (1,1,1,false,'NOT_CREATED','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
    }

    @Test
    @DisplayName("경쟁 실행기는 같은 작업을 한 번만 선점한다")
    void claimsOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await();
                return store.claim();
            });
            var second = pool.submit(() -> {
                barrier.await();
                return store.claim();
            });
            int claimed
                = (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("숨김 출력은 저장하지 않고 만료한 임대의 결과를 거절한다")
    void protectsResults() {
        var lease = store.claim().orElseThrow();
        assertThat(store.prepare(lease)).isTrue();
        assertThat(store.beginCreate(lease, 1)).isTrue();
        assertThat(store.saveToken(lease, 1, "token")).isTrue();
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, BigDecimal.ONE, "secret", null, null);
        assertThat(store.saveResult(lease, 1, "token", result)).isTrue();
        assertThat(jdbc.queryForObject("SELECT actual_output FROM judge_test_results", String.class)).isNull();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.saveResult(lease, 1, "token", result)).isFalse();
    }

    /** 실행 전에 만료된 시험 제출은 원래 접수 식별자를 가진 J4로 종결한다. */
    @Test
    @DisplayName("기한 초과 시험은 EXAM 문맥과 원래 접수 ID를 가진 실패 이벤트로 종결한다")
    void expiresExamWithOriginalReceipt() {
        jdbc.update("""
            UPDATE submissions SET context='EXAM',context_id=55,source_submission_id=71,
                idempotency_key=NULL,request_hash=NULL WHERE id=1
            """);
        given(clock.instant()).willReturn(START.plusSeconds(601));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id=1", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isZero();
        String message = jdbc.queryForObject("SELECT payload FROM outbox_events", String.class);
        var event = JsonMapper.builder().build().readTree(message);
        assertThat(event.get("eventType").asString()).isEqualTo("SubmissionFailed");
        assertThat(event.get("context").asString()).isEqualTo("EXAM");
        assertThat(event.get("contextId").asString()).isEqualTo("55");
        assertThat(event.get("receiptId").asString()).isEqualTo("71");
        assertThat(event.get("errorCode").asString()).isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("현재 회차가 바뀌면 이전 실행 토큰과 결과를 반영하지 않는다")
    void rejectsOldAttempt() {
        var lease = store.claim().orElseThrow();
        assertThat(store.beginCreate(lease, 1)).isTrue();
        jdbc.update("UPDATE submissions SET judge_attempt=2 WHERE id=1");
        assertThat(store.saveToken(lease, 1, "late-token")).isFalse();
        assertThat(store.active(lease)).isFalse();
    }

    @Test
    @DisplayName("종결 결과와 Outbox는 한 번만 저장하고 숨김 결과도 대표 판정에 포함한다")
    void completesOnce() {
        var lease = store.claim().orElseThrow();
        store.beginCreate(lease, 1);
        store.saveToken(lease, 1, "token");
        store
            .saveResult(
                lease,
                1,
                "token",
                new JudgeTestResult(State.COMPLETED, JudgeVerdict.WA, 12, BigDecimal.TEN, "private", null, null)
            );
        var data
            = new com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse(
                "1",
                "1",
                "제목",
                1,
                List.of("구현"),
                List.of("3"),
                null,
                null,
                List.of(),
                List.of()
            );
        assertThat(store.complete(lease, data)).isTrue();
        assertThat(store.complete(lease, data)).isFalse();
        assertThat(jdbc.queryForObject("SELECT verdict FROM submissions", String.class)).isEqualTo("WA");
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .isEqualTo(lease.correlationId())
            .matches("sched-JudgeJobExecutor-[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("대기 상태의 기한 초과는 실행 시도를 만들지 않고 실패 이벤트와 함께 종결한다")
    void expiresWithoutRun() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        metrics.bindTo(registry);
        assertThat(registry.get("judge.jobs.pending.count").gauge().value()).isEqualTo(1);
        assertThat(registry.get("judge.jobs.failed").functionCounter().count()).isZero();
        given(clock.instant()).willReturn(START.plusSeconds(600));
        assertThat(store.claim()).isEmpty();
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .matches("sched-JudgeJobExpiry-[0-9a-f-]{36}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(registry.get("judge.jobs.pending.count").gauge().value()).isZero();
        assertThat(registry.get("judge.jobs.failed").functionCounter().count()).isEqualTo(1);
        assertThat(registry.get("judge.jobs.failed").functionCounter().count()).isEqualTo(1);
        var payload
            = JsonMapper
                .builder()
                .build()
                .readTree(jdbc.queryForObject("SELECT payload FROM outbox_events", String.class));
        assertThat(payload.get("correlationId").asString())
            .isEqualTo(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class));
        registry.close();
    }

    @Test
    @DisplayName("확정 시스템 실패는 백오프 뒤 다음 시도로 선점하고 불명확한 생성은 재시도하지 않는다")
    void retriesOnlyKnownFailure() {
        var first = store.claim().orElseThrow();
        assertThat(store.fail(first, "TEST_DATA_UNAVAILABLE")).isTrue();
        assertThat(store.claim()).isEmpty();
        given(clock.instant()).willReturn(START.plusSeconds(10));
        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(store.beginCreate(second, 1)).isTrue();
        assertThat(store.fail(second, "JUDGE0_UNAVAILABLE")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RUNNING");
    }

    /** 확정 거부는 원자적으로 재시도하고 이전 임대의 거부 응답은 현재 요청을 변경하지 않는지 확인한다. */
    @Test
    @DisplayName("확정 생성 거부는 백오프 재시도로 전환하고 늦은 거부는 무시한다")
    void retriesRejectedCreate() {
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        assertThat(store.rejectCreate(first, 1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RETRY_WAITING");
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("NOT_CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(1);
        given(clock.instant()).willReturn(START.plusSeconds(10));
        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.beginCreate(second, 1)).isTrue();
        assertThat(store.rejectCreate(first, 1)).isFalse();
        assertThat(store.saveToken(second, 1, "token")).isTrue();
        assertThat(store.rejectCreate(second, 1)).isFalse();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("CREATED");
    }

    /** 회수 전 토큰과 늦은 실행기의 쓰기를 함께 검증한다. */
    @Test
    @DisplayName("시험 임대 회수 후 기존 토큰과 64KiB를 넘는 컴파일 메시지를 보존한다")
    void recoversExamLease() {
        jdbc.update("""
            UPDATE submissions SET context='EXAM',context_id=55,source_submission_id=71,
                idempotency_key=NULL,request_hash=NULL WHERE id=1
            """);
        jdbc.update("""
            INSERT INTO judge_test_results(judge_job_id,test_asset_id,test_order,is_sample,token_status,
                created_at,updated_at)
            SELECT judge_job_id,2,2,false,'NOT_CREATED',created_at,updated_at FROM judge_test_results
            """);
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        assertThat(store.saveToken(first, 1, "completed-token")).isTrue();
        String diagnostic = "오류".repeat(12_000);
        var compileError = new JudgeTestResult(State.COMPLETED, JudgeVerdict.CE, null, null, null, null, diagnostic);
        assertThat(store.saveResult(first, 1, "completed-token", compileError)).isTrue();
        assertThat(store.beginCreate(first, 2)).isTrue();
        assertThat(store.saveToken(first, 2, "existing-token")).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(119));
        assertThat(store.recoverOne()).isFalse();

        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT result FROM judge_job_runs", String.class)).isEqualTo("LEASE_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("QUEUED");
        assertThat(store.heartbeat(first)).isFalse();

        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.completedOrders(second)).containsExactly(1);
        assertThat(store.resumableTokens(second)).isEqualTo(Map.of(2, "existing-token"));
        assertThat(store.beginCreate(second, 1)).isFalse();
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, BigDecimal.ONE, null, null, null);
        assertThat(store.beginCreate(second, 2)).isFalse();
        assertThat(store.saveResult(first, 2, "existing-token", result)).isFalse();
        assertThat(store.saveResult(second, 2, "different-token", result)).isFalse();
        assertThat(store.saveResult(second, 2, "existing-token", result)).isTrue();
        assertThat(jdbc.queryForList("""
            SELECT created_try_count FROM judge_test_results ORDER BY test_order
            """, Integer.class)).containsExactly(1, 1);
        assertThat(store.complete(second, null)).isTrue();
        assertThat(store.saveResult(first, 2, "existing-token", result)).isFalse();
        assertThat(store.complete(first, null)).isFalse();
        assertThat(store.complete(second, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT verdict FROM submissions", String.class)).isEqualTo("CE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
            .isEqualTo("ExamSubmissionJudged");
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .isEqualTo(second.correlationId());
        assertThat(jdbc.queryForObject("SELECT compile_message FROM submissions", String.class)).isEqualTo(diagnostic);
    }

    /** 생성 여부가 불명확한 테스트의 재생성 대기와 마지막 임대 회수의 종결을 확인한다. */
    @Test
    @DisplayName("UNKNOWN은 유예 후 다음 시도에서 재생성하고 시도 상한에서 실패 Outbox를 한 번 남긴다")
    void waitsBeforeRetryingUnknownAndExhaustsLease() {
        var first = store.claim().orElseThrow();
        given(clock.instant()).willReturn(START.plusSeconds(119));
        assertThat(store.beginCreate(first, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.claim()).isEmpty();

        given(clock.instant()).willReturn(START.plusSeconds(149));
        var second = store.claim().orElseThrow();
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.resumableTokens(second)).isEmpty();
        assertThat(store.beginCreate(second, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(269));
        assertThat(store.recoverOne()).isTrue();
        var third = store.claim().orElseThrow();
        assertThat(third.tryCount()).isEqualTo(3);
        assertThat(store.prepare(third)).isTrue();
        assertThat(store.beginCreate(third, 1)).isTrue();

        given(clock.instant()).willReturn(START.plusSeconds(389));
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("NOT_CREATED");
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM judge_jobs", String.class)).isEqualTo("LEASE_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 이전 시도의 응답 유실이 새 시도의 임대나 재생성 횟수에 영향을 주지 않는지 확인한다. */
    @Test
    @DisplayName("새 시도를 선점하고 준비하기 전에는 이전 시도의 UNKNOWN으로 임대를 회수하지 않는다")
    void keepsNewLeaseWhenPreviousAttemptIsUnknown() {
        var first = store.claim().orElseThrow();
        given(clock.instant()).willReturn(START.plusSeconds(119));
        assertThat(store.beginCreate(first, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.recoverOne()).isTrue();

        given(clock.instant()).willReturn(START.plusSeconds(149));
        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(2);
        assertThat(store.recoverUnknownOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM judge_jobs WHERE id=1", String.class))
            .isEqualTo(second.token());
        assertThat(jdbc.queryForObject("SELECT unknown_retry_count FROM judge_jobs WHERE id=1", Integer.class))
            .isZero();
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.beginCreate(second, 1)).isTrue();
    }

    /** 별도 UNKNOWN 복구가 임대를 교체하며 동일 시도에서 재생성 두 번을 제한하는지 확인한다. */
    @Test
    @DisplayName("UNKNOWN은 30초 후 같은 시도에서 두 번 재생성하고 세 번째 유실은 시스템 실패로 닫는다")
    void recoversUnknownBeforeLeaseExpires() {
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(29));
        assertThat(store.recoverUnknownOne()).isFalse();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        assertThat(store.recoverOne()).isFalse();
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(store.recoverUnknownOne()).isFalse();

        var second = store.claim().orElseThrow();
        assertThat(second.tryCount()).isEqualTo(first.tryCount());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(store.prepare(second)).isTrue();
        assertThat(store.beginCreate(second, 1)).isTrue();
        assertThat(store.saveToken(first, 1, "late-token")).isFalse();
        given(clock.instant()).willReturn(START.plusSeconds(60));
        assertThat(store.recoverUnknownOne()).isTrue();

        var third = store.claim().orElseThrow();
        assertThat(third.tryCount()).isEqualTo(first.tryCount());
        assertThat(store.prepare(third)).isTrue();
        assertThat(store.beginCreate(third, 1)).isTrue();
        given(clock.instant()).willReturn(START.plusSeconds(90));
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(store.recoverUnknownOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT error_message FROM judge_job_runs", String.class))
            .isEqualTo("JUDGE0_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_jobs", Integer.class)).isEqualTo(2);

        given(clock.instant()).willReturn(START.plusSeconds(100));
        var nextAttempt = store.claim().orElseThrow();
        assertThat(store.prepare(nextAttempt)).isTrue();
        assertThat(jdbc.queryForObject("SELECT unknown_retry_count FROM judge_jobs", Integer.class)).isZero();
    }

    /** 생성 응답 유실 뒤 새 임대에서 확정한 결과만 시험 결과 이벤트로 발행한다. */
    @Test
    @DisplayName("생성 응답 유실 복구 후 늦은 토큰을 거절하고 새 결과와 이벤트를 한 번 저장한다")
    void completesAfterUnknownRecovery() {
        // given: 생성 요청 이후 응답을 받지 못해 토큰은 UNKNOWN으로 남는다.
        jdbc.update("""
            UPDATE submissions SET context='EXAM',context_id=55,source_submission_id=71,
                idempotency_key=NULL,request_hash=NULL WHERE id=1
            """);
        var first = store.claim().orElseThrow();
        assertThat(store.beginCreate(first, 1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("UNKNOWN");

        // when: 유예가 지나 회수된 임대에서 새 실행을 생성한다.
        given(clock.instant()).willReturn(START.plusSeconds(30));
        assertThat(store.recoverUnknownOne()).isTrue();
        var next = store.claim().orElseThrow();
        assertThat(store.prepare(next)).isTrue();
        assertThat(store.beginCreate(next, 1)).isTrue();
        assertThat(store.saveToken(first, 1, "lost-token")).isFalse();
        assertThat(store.saveToken(next, 1, "new-token")).isTrue();
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, BigDecimal.ONE, null, null, null);
        assertThat(store.saveResult(next, 1, "new-token", result)).isTrue();
        assertThat(store.complete(next, null)).isTrue();

        // then: 이전 실행과 완료 재호출은 확정 결과를 덮어쓰거나 이벤트를 추가하지 못한다.
        assertThat(store.saveResult(first, 1, "lost-token", result)).isFalse();
        assertThat(store.complete(first, null)).isFalse();
        assertThat(store.complete(next, null)).isFalse();
        assertThat(jdbc.queryForObject("SELECT verdict FROM submissions", String.class)).isEqualTo("AC");
        assertThat(jdbc.queryForObject("SELECT judge0_token FROM judge_test_results", String.class))
            .isEqualTo("new-token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
            .isEqualTo("ExamSubmissionJudged");
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .isEqualTo(next.correlationId());
    }

    /**
     * 실제 작업 저장소와 실행기를 연결해 생성 응답 유실 후 복구된 결과를 확인한다.
     *
     * @throws InterruptedException 테스트 자료 대역을 설정하는 중 인터럽트된 경우
     */
    @Test
    @DisplayName("실행 생성 응답 유실 뒤 복구된 실행기가 새 토큰의 결과와 이벤트를 한 번 저장한다")
    void workerCompletesAfterLostCreateResponse() throws InterruptedException {
        // given: Judge0와 자료 조회만 대역으로 두고 작업·결과·Outbox는 실제 DB를 사용한다.
        jdbc.update("""
            INSERT INTO execution_runtimes(snapshot_hash,language,judge0_language_id,
                runtime_name,runtime_version,created_at)
            VALUES (REPEAT('e',64),'java',91,'Java','17','2026-10-31 09:00:00')
            ON DUPLICATE KEY UPDATE id=id
            """);
        jdbc.update("""
            UPDATE submissions SET context='EXAM',context_id=55,source_submission_id=71,
                idempotency_key=NULL,request_hash=NULL,
                runtime_id=(SELECT id FROM execution_runtimes WHERE snapshot_hash=REPEAT('e',64)) WHERE id=1
            """);
        var data
            = new SubmissionJudgingDataResponse(
                "1",
                "1",
                "제목",
                1,
                List.of(),
                List.of(),
                new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null)),
                new ProblemValidationDataResponse.Limits(1000, 6000, 1024, 64),
                List
                    .of(
                        new SubmissionJudgingDataResponse.TestCase(
                            "1",
                            1,
                            false,
                            new SubmissionJudgingDataResponse.Asset("test", "v1", "a".repeat(64))
                        )
                    ),
                List.of()
            );
        var dataClient = mock(SubmissionJudgingDataClient.class);
        var assets = mock(TestAssetClient.class);
        var languages = mock(JudgeLanguageService.class);
        var generator = mock(ExecutionCodeGenerator.class);
        var client = mock(JudgeClient.class);
        var evaluator = mock(JudgeResultEvaluator.class);
        var runtime = new JudgeRuntime("java", 91, "Java", "17", null);
        var response = new JudgeExecutionResultResponse(State.COMPLETED, 3, "1", null, null, null, null, null, 0, null);
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, null, "1", null, null);
        given(dataClient.find(1, 1)).willReturn(data);
        given(assets.read(any())).willReturn(new SubmissionJudgingDataResponse.TestContent(Map.of(), "1"));
        given(generator.language()).willReturn(JudgeLanguage.JAVA);
        given(generator.generate(any(), eq("x"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        given(client.create(any()))
            .willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED))
            .willReturn(new JudgeExecutionCreateResponse("recovered-token", runtime));
        given(client.find("recovered-token")).willReturn(response);
        given(evaluator.evaluate(eq(response), any(), eq("1"), eq(1024), eq(64))).willReturn(result);
        var worker
            = new SubmissionWorker(
                store,
                dataClient,
                assets,
                languages,
                List.of(generator),
                client,
                evaluator,
                clock,
                new JudgeEngineCircuit(clock)
            );

        // when: 첫 호출의 응답 유실을 기록하고 30초 후 UNKNOWN을 회수한다.
        assertThat(worker.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_test_results", String.class))
            .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
        given(clock.instant()).willReturn(START.plusSeconds(30));
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(worker.runOnce()).isTrue();

        // then: 두 번째 실행에서만 받은 토큰의 결과와 시험 결과 이벤트를 확정한다.
        verify(client, times(2)).create(any());
        verify(client).find("recovered-token");
        assertThat(jdbc.queryForObject("SELECT verdict FROM submissions", String.class)).isEqualTo("AC");
        assertThat(jdbc.queryForObject("SELECT judge0_token FROM judge_test_results", String.class))
            .isEqualTo("recovered-token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class))
            .isEqualTo("ExamSubmissionJudged");
    }

    /** 재선점 없이 기한이 끝나도 이미 시작한 동일 시도의 이력이 한 건 남는지 확인한다. */
    @Test
    @DisplayName("UNKNOWN 복구 대기 중 기한이 끝나면 직전 임대의 실행 이력과 실패 이벤트를 한 번 남긴다")
    void closesStartedAttemptWhenRecoveryWaitReachesDeadline() {
        given(clock.instant()).willReturn(START.plusSeconds(550));
        var lease = store.claim().orElseThrow();
        assertThat(store.beginCreate(lease, 1)).isTrue();

        given(clock.instant()).willReturn(START.plusSeconds(580));
        assertThat(store.recoverUnknownOne()).isTrue();
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.saveToken(lease, 1, "late-token")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RETRY_WAITING");

        given(clock.instant()).willReturn(START.plusSeconds(600));
        assertThat(store.claim()).isEmpty();
        assertThat(store.recoverOne()).isTrue();
        assertThat(store.recoverOne()).isFalse();
        assertThat(jdbc.queryForMap("SELECT try_count,lease_token,result,error_message FROM judge_job_runs"))
            .containsEntry("try_count", 1)
            .containsEntry("lease_token", lease.token())
            .containsEntry("result", "SYSTEM_ERROR")
            .containsEntry("error_message", "DEADLINE_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM judge_jobs", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 재시도 작업과 제출의 상태 대응표를 지키는지 확인한다. */
    @Test
    @DisplayName("시스템 오류 재시도는 작업 RETRY_WAITING과 제출 QUEUED를 함께 저장한다")
    void queuesSubmissionOnSystemRetry() {
        var lease = store.claim().orElseThrow();
        assertThat(store.fail(lease, "JUDGE0_UNAVAILABLE")).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM judge_jobs", String.class)).isEqualTo("RETRY_WAITING");
        assertThat(jdbc.queryForObject("SELECT status FROM submissions", String.class)).isEqualTo("QUEUED");
    }

    /** 서킷 차단 반환이 시도·기한·실행 이력을 소진하지 않는지 확인한다. */
    @Test
    @DisplayName("미호출 선점 반환은 같은 시도와 기한을 유지하고 이전 실행기의 저장을 차단한다")
    void returnsUncalledClaimWithoutConsumingAttempt() {
        var lease = store.claim().orElseThrow();
        assertThat(store.releaseUncalled(lease)).isTrue();
        assertThat(store.releaseUncalled(lease)).isFalse();
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_jobs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_job_runs", Integer.class)).isZero();
        assertThat(store.saveToken(lease, 1, "late-token")).isFalse();
        var next = store.claim().orElseThrow();
        assertThat(next.deadlineAt()).isEqualTo(lease.deadlineAt());
        assertThat(next.tryCount()).isEqualTo(lease.tryCount());
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        Clock testClock() {
            return mock(Clock.class);
        }

        @Bean
        ObjectMapper mapper() {
            return JsonMapper.builder().build();
        }
    }
}
