package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.backtoback.judge.judging.service.dto.SubmissionExecutionInput;
import com.backtoback.judge.judging.service.dto.SubmissionLease;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;

@ExtendWith(MockitoExtension.class)
class SubmissionWorkerTest {
    @Mock
    private SubmissionExecutionStore store;
    @Mock
    private SubmissionJudgingDataClient dataClient;
    @Mock
    private TestAssetClient assets;
    @Mock
    private JudgeLanguageService languages;
    @Mock
    private ExecutionCodeGenerator generator;
    @Mock
    private JudgeClient client;
    @Mock
    private JudgeResultEvaluator evaluator;
    private SubmissionWorker worker;
    private JudgeEngineCircuit circuit;
    private SubmissionJudgingDataResponse data;
    private final JudgeRuntime runtime = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    private final SubmissionLease lease
        = new SubmissionLease(1, 1, 1, 1, "lease", LocalDateTime.of(2026, 10, 31, 9, 10));

    /**
     * 실행기와 공통 선점·자료 조회 조건을 준비한다.
     *
     * @throws InterruptedException 테스트 자료 대역 설정 중 중단된 경우
     */
    @BeforeEach
    void prepare() throws InterruptedException {
        circuit = new JudgeEngineCircuit(Clock.systemUTC());
        worker
            = new SubmissionWorker(
                store,
                dataClient,
                assets,
                languages,
                List.of(generator),
                client,
                evaluator,
                Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC),
                circuit
            );
        data
            = new SubmissionJudgingDataResponse(
                "1",
                "1",
                "제목",
                1,
                List.of("구현"),
                List.of("3"),
                new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null)),
                new ProblemValidationDataResponse.Limits(1000, 6000, 1024, 64),
                List
                    .of(
                        new SubmissionJudgingDataResponse.TestCase(
                            "1",
                            1,
                            true,
                            new SubmissionJudgingDataResponse.Asset("first", "v1", "a".repeat(64))
                        ),
                        new SubmissionJudgingDataResponse.TestCase(
                            "2",
                            2,
                            false,
                            new SubmissionJudgingDataResponse.Asset("second", "v1", "b".repeat(64))
                        )
                    ),
                List.of()
            );
        given(store.claim()).willReturn(Optional.of(lease));
        given(store.active(lease)).willReturn(true);
        given(store.input(lease)).willReturn(new SubmissionExecutionInput(1, 1, "java", "code", runtime));
        given(dataClient.find(1, 1)).willReturn(data);
        given(store.prepare(lease)).willReturn(true);
        given(generator.language()).willReturn(JudgeLanguage.JAVA);
        given(assets.read(any())).willReturn(new SubmissionJudgingDataResponse.TestContent(Map.of(), "1"));
    }

    /**
     * 코드 오답 이후에도 나머지 테스트의 고정 자료로 실행하고 종결하는지 확인한다.
     *
     * @throws InterruptedException 테스트 자료 대역 검증 중 중단된 경우
     */
    @Test
    @DisplayName("첫 테스트가 WA여도 전체 테스트를 실행한 뒤 종결한다")
    void executesAll() throws InterruptedException {
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));

        given(store.beginCreate(eq(lease), anyInt())).willReturn(true);
        given(client.create(any())).willAnswer(invocation -> {
            assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo(lease.correlationId());
            return new JudgeExecutionCreateResponse("token", runtime);
        });
        given(client.find("token"))
            .willReturn(
                new JudgeExecutionResultResponse(State.COMPLETED, 3, "1", null, null, null, null, null, 0, null)
            );
        given(store.saveToken(eq(lease), anyInt(), eq("token"))).willReturn(true);
        given(store.saveResult(eq(lease), anyInt(), any(), any())).willReturn(true);
        given(evaluator.evaluate(any(), any(), eq("1"), eq(1024), eq(64)))
            .willReturn(
                new JudgeTestResult(State.COMPLETED, JudgeVerdict.WA, 1, null, "0", null, null),
                new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, null, "1", null, null)
            );
        assertThat(worker.runOnce()).isTrue();
        verify(assets).read(data.cases().get(0).asset());
        verify(assets).read(data.cases().get(1).asset());
        verify(client, times(2)).create(any());
        verify(store).complete(lease, data);
    }

    /** 임대 회수 후 결과 조회가 타임아웃되어도 같은 토큰으로 재조회해 채점을 마치는지 확인한다. */
    @Test
    @DisplayName("회수한 테스트의 결과 조회가 타임아웃되면 같은 Judge0 토큰으로 재조회해 완료한다")
    void retriesTimedOutPollWithResumedToken() {
        given(store.completedOrders(lease)).willReturn(java.util.Set.of(1));
        given(store.resumableTokens(lease)).willReturn(Map.of(2, "second"));
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        var response = new JudgeExecutionResultResponse(State.COMPLETED, 3, "1", null, null, null, null, null, 0, null);
        var result = new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, null, "1", null, null);
        given(client.find("second"))
            .willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED))
            .willReturn(response);
        given(evaluator.evaluate(eq(response), any(), eq("1"), eq(1024), eq(64))).willReturn(result);
        given(store.saveResult(lease, 2, "second", result)).willReturn(true);

        assertThat(worker.runOnce()).isTrue();
        verify(client, never()).find("first");
        verify(client, times(2)).find("second");
        verify(client, never()).create(any());
        verify(store).saveResult(lease, 2, "second", result);
        verify(store).complete(lease, data);
    }

    /** 미확정 외부 제출은 고정 환경을 저장한 뒤 그 환경으로 Judge0를 호출한다. */
    @Test
    @DisplayName("외부 제출은 런타임과 테스트를 먼저 확정한 뒤 해당 런타임으로 실행한다")
    void initializesExternalBeforeExecution() {
        given(store.input(lease)).willReturn(new SubmissionExecutionInput(1, 1, "java", "code", null));
        given(store.initializeExternal(lease, data, "java")).willReturn(Optional.of(runtime));
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        given(store.beginCreate(lease, 1)).willReturn(true);
        given(client.create(any())).willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED));

        assertThat(worker.runOnce()).isTrue();

        var order = inOrder(store, languages, client);
        order.verify(store).initializeExternal(lease, data, "java");
        order.verify(store).validateCases(lease, data);
        order.verify(languages).requireRuntime("java", runtime);
        order.verify(client).create(any());
    }

    /** 선점 후 첫 HTTP 호출 전에 서킷이 열리면 미호출 작업을 같은 시도에 반환한다. */
    @Test
    @DisplayName("선점 후 자료 조회 중 서킷이 열리면 실행 생성 없이 임대를 반환한다")
    void releasesUncalledClaimWhenCircuitOpens() {
        given(dataClient.find(1, 1)).willAnswer(call -> {
            try (var other = circuit.acquire().orElseThrow()) {
                for (int index = 0; index < 5; index++) {
                    try {
                        other.invoke(() -> {
                            throw new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED);
                        });
                    } catch (JudgeException expected) {
                        // 다른 워커의 연속 통신 실패를 재현한다.
                    }
                }
            }
            return data;
        });
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        assertThat(worker.runOnce()).isTrue();
        verify(store).releaseUncalled(lease);
        verify(store, never()).beginCreate(any(), anyInt());
        verify(client, never()).create(any());
        verify(store, never()).fail(any(), any());
    }

    /** 생성기 오류가 Judge0 장애로 기록되거나 외부 실행을 생성하지 않는지 확인한다. */
    @Test
    @DisplayName("코드 생성 실패는 자료 오류로 기록하고 Judge0를 호출하지 않는다")
    void classifiesGenerationFailure() {
        given(generator.generate(any(), eq("code"), any())).willThrow(new IllegalArgumentException("invalid input"));
        assertThat(worker.runOnce()).isTrue();
        verify(store).fail(lease, "TEST_DATA_UNAVAILABLE");
        verify(client, never()).create(any());
    }

    /** 생성 응답 유실 시 중복 실행이나 확정 실패 처리를 하지 않는지 확인한다. */
    @Test
    @DisplayName("생성 응답을 잃으면 새 실행이나 실패 재시도를 만들지 않는다")
    void keepsUnknown() {
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));

        given(store.beginCreate(eq(lease), anyInt())).willReturn(true);
        given(client.create(any())).willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED));
        assertThat(worker.runOnce()).isTrue();
        verify(client, times(1)).create(any());
        verify(store, never()).complete(any(), any());
        verify(store, never()).fail(any(), any());
    }

    /**
     * 중단을 자료 장애로 기록하거나 외부 실행으로 이어가지 않는지 확인한다.
     *
     * @throws InterruptedException 테스트 자료 대역 설정 중 중단된 경우
     */
    @Test
    @DisplayName("자료 읽기 중 인터럽트는 실패 기록 없이 실행을 중단한다")
    void stopsOnInterrupt() throws InterruptedException {
        given(assets.read(any())).willThrow(new InterruptedException());
        try {
            assertThat(worker.runOnce()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store, never()).fail(any(), any());
            verify(client, never()).create(any());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * 확정 거부만 재시도 처리로 넘기는지 확인한다.
     *
     * @param status 확정 거부 HTTP 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            400,
            401,
            403,
            422,
            429
        }
    )
    @DisplayName("Judge0의 확정 4xx 거부는 현재 요청의 실패 처리로 넘긴다")
    void retriesRejectedCreate(int status) {
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        given(store.beginCreate(eq(lease), anyInt())).willReturn(true);
        given(client.create(any())).willThrow(new JudgeException(JudgeErrorCode.JUDGE0_RESPONSE_FAILED, status));
        assertThat(worker.runOnce()).isTrue();
        verify(store).rejectCreate(lease, 1);
        verify(client, times(1)).create(any());
        verify(store, never()).saveToken(any(), anyInt(), any());
    }

    /**
     * 서버 오류와 요청 시간 초과를 확정 거부로 해석하지 않는지 확인한다.
     *
     * @param status 생성 여부가 불명확한 HTTP 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            408,
            500,
            502,
            503,
            504
        }
    )
    @DisplayName("타임아웃과 서버 오류는 생성 여부가 불명확하므로 재시도하지 않는다")
    void keepsAmbiguousResponse(int status) {
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        given(store.beginCreate(eq(lease), anyInt())).willReturn(true);
        given(client.create(any())).willThrow(new JudgeException(JudgeErrorCode.JUDGE0_RESPONSE_FAILED, status));
        assertThat(worker.runOnce()).isTrue();
        verify(store, never()).rejectCreate(any(), anyInt());
        verify(store, never()).fail(any(), any());
    }

}
