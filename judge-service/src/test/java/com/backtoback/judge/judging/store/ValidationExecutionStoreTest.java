package com.backtoback.judge.judging.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.ValidationProgressService;
import com.backtoback.judge.judging.service.dto.ValidationLease;
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
        ValidationExecutionStore.class,
        ValidationProgressService.class,
        ValidationExecutionStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ValidationExecutionStoreTest {
    private static final Instant START = Instant.parse("2026-10-31T00:00:00Z");
    private static final JudgeRuntime RUNTIME = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ValidationExecutionStore store;
    @Autowired
    private ValidationProgressService progress;
    @Autowired
    private Clock clock;

    /**
     * 격리 DB 연결을 등록한다.
     *
     * @param registry 동적 설정
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 이전 테스트의 실행 상태를 제거하고 고정 기한 작업을 준비한다. */
    @BeforeEach
    void prepare() {
        given(clock.instant()).willReturn(START);
        jdbc.update("DELETE FROM judge_validation_test_results");
        jdbc.update("DELETE FROM judge_validation_runs");
        jdbc.update("DELETE FROM judge_validation_jobs");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("""
            INSERT INTO judge_validation_jobs (validation_id,problem_revision_id,language,request_hash,correlation_id,
            status,try_count,next_run_at,deadline_at,created_at,updated_at)
            VALUES (2001,1001,'java',?,'original-trace','QUEUED',0,'2026-10-31 09:00:00','2026-10-31 09:10:00',
            '2026-10-31 09:00:00','2026-10-31 09:00:00')
            """, "a".repeat(64));
    }

    /**
     * 여러 실행기가 같은 작업의 실행 권한을 중복 획득하지 않는다.
     *
     * @throws Exception 경합 실행 실패
     */
    @Test
    @DisplayName("동시 선점은 한 실행기만 성공하고 시도 번호는 한 번 증가한다")
    void claimsOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store.claim();
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store.claim();
            });
            assertThat(
                List
                    .of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream()
                    .filter(java.util.Optional::isPresent)
                    .count()
            ).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_validation_jobs", Integer.class)).isEqualTo(1);
    }

    /** 정상 종결의 세 상태와 이벤트가 한 번만 저장된다. */
    @Test
    @DisplayName("통과 판정은 실행 환경·최종 시도·Outbox를 함께 저장하고 재종결하지 않는다")
    void completesAtomically() {
        var lease = ready();
        result(lease, JudgeVerdict.AC);
        assertThat(store.complete(lease, "java-runner-v1")).isTrue();
        assertThat(store.complete(lease, "java-runner-v1")).isFalse();
        var response = progress.find(2001);
        assertThat(response.status()).isEqualTo("PASSED");
        assertThat(response.tests().getFirst().verdict()).isEqualTo("AC");
        assertThat(response.runtimeKey()).hasSize(64);
        assertThat(response.runnerVersion()).isEqualTo("java-runner-v1");
        assertThat(response.maxTimeMs()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events", String.class))
            .isEqualTo("original-trace");
    }

    /** 서로 다른 실패 판정을 유지하면서 정상 처리 이력을 기록한다. */
    @Test
    @DisplayName("WA와 TLE가 함께 있어도 대표 판정 없이 REJECTED와 SUCCEEDED를 저장한다")
    void rejectsWithIndividualResults() {
        var lease = ready();
        result(lease, JudgeVerdict.WA);
        jdbc.update("""
                    INSERT INTO judge_validation_test_results
                    (validation_id,test_asset_id,test_order,token_status,created_try_count,verdict,
            time_ms,created_at,updated_at)
                    VALUES (2001,502,2,'DONE',1,'TLE',2000,NOW(),NOW())
                    """);
        assertThat(store.complete(lease, "java-runner-v1")).isTrue();
        var response = progress.find(2001);
        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.tests()).extracting(test -> test.verdict()).containsExactly("WA", "TLE");
        assertThat(response.maxTimeMs()).isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT result FROM judge_validation_runs", String.class))
            .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForMap("SELECT * FROM judge_validation_runs")).doesNotContainKey("verdict");
    }

    /** 이전 시도의 완료 행을 현재 시도의 완료로 재사용하지 않는다. */
    @Test
    @DisplayName("미완료·이전 시도·시스템 오류 테스트가 있으면 정상 종결을 거부한다")
    void rejectsIncompleteResults() {
        var lease = ready();
        assertThatThrownBy(() -> store.complete(lease, "java-runner-v1")).isInstanceOf(IllegalArgumentException.class);
        result(lease, JudgeVerdict.AC);
        jdbc.update("UPDATE judge_validation_test_results SET created_try_count=2");
        assertThatThrownBy(() -> store.complete(lease, "java-runner-v1")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE judge_validation_test_results SET created_try_count=1,verdict='SYSTEM_ERROR'");
        assertThatThrownBy(() -> store.complete(lease, "java-runner-v1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_validation_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** Outbox INSERT 실패는 종결 작업과 시도 이력까지 롤백한다. */
    @Test
    @DisplayName("Outbox 실패 시 최종 상태와 시도 이력도 롤백된다")
    void rollsBackCompletion() {
        var lease = ready();
        result(lease, JudgeVerdict.WA);
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT injected_failure CHECK (aggregate_id <> '2001')");
        try {
            assertThatThrownBy(() -> store.complete(lease, "java-runner-v1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(progress.find(2001).status()).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_validation_runs", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CHECK injected_failure");
        }
    }

    /** 소유권을 잃은 실행기의 결과와 임대 연장을 차단한다. */
    @Test
    @DisplayName("임대 토큰이 바뀌면 늦은 결과·종결·갱신을 모두 거부한다")
    void rejectsStaleLease() {
        var lease = ready();
        jdbc.update("UPDATE judge_validation_jobs SET lease_token='new-owner'");
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.beginCreate(lease, 1)).isFalse();
        assertThat(store.complete(lease, "java-runner-v1")).isFalse();
        assertThat(store.fail(lease, null)).isFalse();
    }

    /** 기한과 같은 시각은 추가 실행이나 정상 종결을 허용하지 않는다. */
    @Test
    @DisplayName("기한 경계에서 정상 결과를 거부하고 실행 시도를 시스템 오류로 종결한다")
    void expiresRunningJob() {
        var lease = ready();
        given(clock.instant()).willReturn(START.plusSeconds(600));
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.complete(lease, "java-runner-v1")).isFalse();
        assertThat(store.expireOne()).isTrue();
        assertThat(store.expireOne()).isFalse();
        assertThat(progress.find(2001).status()).isEqualTo("ERROR");
        assertThat(progress.find(2001).tests()).allSatisfy(test -> assertThat(test.verdict()).isNull());
        assertThat(jdbc.queryForObject("SELECT result FROM judge_validation_runs", String.class))
            .isEqualTo("SYSTEM_ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 대기 상태의 기한 초과는 실행 이력을 꾸며내지 않는다. */
    @Test
    @DisplayName("실행 전 기한 초과는 시도 증가 없이 ERROR와 결과 이벤트만 저장한다")
    void expiresWaitingJob() {
        given(clock.instant()).willReturn(START.plusSeconds(600));
        assertThat(store.claim()).isEmpty();
        assertThat(store.expireOne()).isTrue();
        assertThat(progress.find(2001).maxTimeMs()).isNull();
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_validation_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_validation_runs", Integer.class)).isZero();
    }

    /** 시스템 실패는 코드 판정과 구분되며 시도 상한을 넘지 않는다. */
    @Test
    @DisplayName("시스템 실패는 10초·30초 뒤 재시도하고 세 번째 실패에서만 ERROR 이벤트를 만든다")
    void retriesBoundedly() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            var lease = store.claim().orElseThrow();
            assertThat(lease.attempt()).isEqualTo(attempt);
            assertThat(store.fail(lease, null)).isTrue();
            assertThat(progress.find(2001).tests()).allSatisfy(test -> assertThat(test.verdict()).isNull());
            if (attempt < 3) {
                assertThat(progress.find(2001).status()).isEqualTo("RETRY_WAITING");
                assertThat(store.claim()).isEmpty();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
                given(clock.instant()).willReturn(START.plusSeconds(attempt == 1 ? 10 : 40));
            }
        }
        assertThat(progress.find(2001).status()).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_validation_runs", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** UNKNOWN 상태는 생성 토큰이 확인될 때까지 새 생성으로 바꾸지 않는다. */
    @Test
    @DisplayName("생성 응답 유실 상태에서는 같은 테스트의 생성을 다시 시작하지 않는다")
    void retainsUnknownCreation() {
        var lease = ready();
        assertThat(store.beginCreate(lease, 1)).isTrue();
        assertThat(store.beginCreate(lease, 1)).isFalse();
        assertThat(store.fail(lease, "java-runner-v1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT token_status FROM judge_validation_test_results", String.class))
            .isEqualTo("UNKNOWN");
    }

    /** 만료 임대를 임의로 되살리지 않는다. */
    @Test
    @DisplayName("임대 만료 후에는 기한이 남아도 임대를 갱신하거나 결과를 쓰지 않는다")
    void rejectsExpiredLease() {
        var lease = ready();
        given(clock.instant()).willReturn(START.plusSeconds(120));
        assertThat(store.heartbeat(lease)).isFalse();
        assertThat(store.saveToken(lease, 1, "token")).isFalse();
    }

    /** 작업 없음과 유효하지 않은 ID는 서로 다른 오류다. */
    @Test
    @DisplayName("작업 없음은 404 오류이고 잘못된 ID는 요청 오류다")
    void distinguishesNotFound() {
        assertThatThrownBy(() -> progress.find(999))
            .isInstanceOfSatisfying(
                JudgeException.class,
                error -> assertThat(error.getErrorCode().getCode()).isEqualTo("PROBLEM_VALIDATION_NOT_FOUND")
            );
        assertThatThrownBy(() -> progress.find(0))
            .isInstanceOfSatisfying(
                JudgeException.class,
                error -> assertThat(error.getErrorCode().getCode()).isEqualTo("COMMON_INVALID_REQUEST")
            );
    }

    /**
     * 실행 가능한 작업과 테스트 자리를 준비한다.
     *
     * @return 선점 소유권
     */
    private ValidationLease ready() {
        var lease = store.claim().orElseThrow();
        var spec = new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null));
        var data
            = new ProblemValidationDataResponse(
                "2001",
                "1001",
                "java",
                "a".repeat(64),
                "class Solution {}",
                spec,
                new ProblemValidationDataResponse.Limits(2000, 7000, 262144, 64),
                List.of(new ProblemValidationDataResponse.TestCase("501", false, Map.of(), "3"))
            );
        assertThat(store.prepare(lease, data, RUNTIME)).isTrue();
        return lease;
    }

    /**
     * 테스트 실행이 완료된 저장 상태를 준비한다.
     *
     * @param lease 소유권
     * @param verdict 판정
     */
    private void result(ValidationLease lease, JudgeVerdict verdict) {
        assertThat(store.beginCreate(lease, 1)).isTrue();
        assertThat(store.saveToken(lease, 1, "token")).isTrue();
        assertThat(
            store
                .saveResult(
                    lease,
                    1,
                    new JudgeTestResult(
                        State.COMPLETED,
                        verdict,
                        2,
                        BigDecimal.valueOf(128),
                        "private-return",
                        "private-debug",
                        null
                    )
                )
        ).isTrue();
    }

    /** 테스트 시각과 JSON 매퍼를 제공한다. */
    @TestConfiguration
    static class Beans {
        /**
         * 전이 경계에서 조절할 시각을 제공한다.
         *
         * @return 테스트 Clock
         */
        @Bean
        @Primary
        Clock testClock() {
            return mock(Clock.class);
        }

        /**
         * 공통 이벤트의 JSON 매퍼를 제공한다.
         *
         * @return JSON 매퍼
         */
        @Bean
        ObjectMapper mapper() {
            return JsonMapper.builder().build();
        }
    }
}
