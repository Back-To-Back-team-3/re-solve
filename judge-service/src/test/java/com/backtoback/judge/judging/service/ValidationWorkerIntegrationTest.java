package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.judging.client.ProblemValidationDataClient;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.ValidationExecutionStore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 DB 선점부터 종결·Outbox까지 연결하고 누적 실행 중 임대 갱신을 확인한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        OutboxStorageConfiguration.class,
        ValidationExecutionStore.class,
        ValidationProgressService.class,
        ValidationWorker.class,
        ValidationTestExecutor.class,
        ValidationWorkerIntegrationTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ValidationWorkerIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ValidationWorker worker;
    @Autowired
    private ValidationProgressService progress;
    @Autowired
    private Clock clock;
    @MockitoBean
    private ProblemValidationDataClient dataClient;
    @MockitoBean
    private JudgeLanguageService languages;
    @MockitoBean
    private ExecutionCodeGenerator generator;
    @MockitoBean
    private JudgeClient client;
    @MockitoBean
    private JudgeResultEvaluator evaluator;
    private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));
    private final JudgeRuntime runtime = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);

    /**
     * 격리 DB 연결을 제공한다.
     *
     * @param registry 설정 등록기
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 10분 기한의 신규 작업을 준비한다. */
    @BeforeEach
    void prepare() {
        given(clock.instant()).willAnswer(call -> instant.get());
        jdbc.update("DELETE FROM judge_validation_test_results");
        jdbc.update("DELETE FROM judge_validation_runs");
        jdbc.update("DELETE FROM judge_validation_jobs");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("""
            INSERT INTO judge_validation_jobs
            (validation_id,problem_revision_id,language,request_hash,correlation_id,status,try_count,
            next_run_at,deadline_at,created_at,updated_at)
            VALUES (2001,1001,'java',?,'trace','QUEUED',0,'2026-10-31 09:00:00','2026-10-31 09:10:00',
            '2026-10-31 09:00:00','2026-10-31 09:00:00')
            """, "a".repeat(64));
    }

    /**
     * 짧은 테스트 열 개를 실행하며 실제 DB 임대를 갱신하고 전체 결과를 원자적으로 종결한다.
     *
     * @param rejected 첫 테스트가 오답인지 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("누적 200초 실행에서도 모든 테스트를 저장하고 PASSED 또는 REJECTED와 Outbox를 종결한다")
    void completesLongSequence(boolean rejected) {
        var spec = new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null));
        var cases
            = IntStream
                .rangeClosed(1, 10)
                .mapToObj(
                    order -> new ProblemValidationDataResponse.TestCase(
                        Integer.toString(500 + order),
                        order == 1,
                        Map.of(),
                        rejected && order == 1 ? "2" : "1"
                    )
                )
                .toList();
        var data
            = new ProblemValidationDataResponse(
                "2001",
                "1001",
                "java",
                "a".repeat(64),
                "source",
                spec,
                new ProblemValidationDataResponse.Limits(2000, 7000, 262144, 64),
                cases
            );
        given(dataClient.find(any())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return data;
        });
        given(languages.requireRuntime("java", null)).willReturn(runtime);
        given(generator.language()).willReturn(JudgeLanguage.JAVA);
        given(generator.runnerVersion()).willReturn("java-runner-v1");
        given(generator.generate(any(), any(), any())).willReturn(new ExecutionSource(JudgeLanguage.JAVA, "code", ""));
        given(client.create(any())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new JudgeExecutionCreateResponse("token", runtime);
        });
        var raw = new JudgeExecutionResultResponse(State.COMPLETED, 3, "1", null, null, null, null, null, 0, null);
        given(client.find("token")).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            instant.set(instant.get().plusSeconds(20));
            return raw;
        });
        given(
            evaluator
                .evaluate(
                    any(),
                    any(),
                    any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt()
                )
        )
            .willAnswer(
                call -> new JudgeTestResult(
                    State.COMPLETED,
                    "2".equals(call.getArgument(2)) ? JudgeVerdict.WA : JudgeVerdict.AC,
                    2,
                    null,
                    null,
                    null,
                    null
                )
            );
        assertThat(worker.runOnce()).isTrue();
        var response = progress.find(2001);
        assertThat(response.status()).isEqualTo(rejected ? "REJECTED" : "PASSED");
        assertThat(response.tests()).hasSize(10).allSatisfy(test -> assertThat(test.verdict()).isNotNull());
        assertThat(response.tests().getFirst().verdict()).isEqualTo(rejected ? "WA" : "AC");
        assertThat(jdbc.queryForObject("SELECT result FROM judge_validation_runs", String.class))
            .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT try_count FROM judge_validation_jobs", Integer.class)).isEqualTo(1);
        assertThat(worker.runOnce()).isFalse();
    }

    /** 테스트 Clock과 공통 이벤트 JSON 변환기를 제공한다. */
    @TestConfiguration
    static class Beans {
        /**
         * 전이 시각을 제어한다.
         *
         * @return 가변 테스트 Clock
         */
        @Bean
        @Primary
        Clock testClock() {
            return mock(Clock.class);
        }

        /**
         * 공통 Outbox의 JSON 변환기를 제공한다.
         *
         * @return JSON 매퍼
         */
        @Bean
        ObjectMapper mapper() {
            return JsonMapper.builder().build();
        }
    }
}
