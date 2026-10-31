package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.dto.ValidationLease;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.dto.ExecutionSource;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.ValidationExecutionStore;

/** 단일 테스트 실행에서 원문 생성·토큰 보존·늦은 응답 차단을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ValidationTestExecutorTest {
    @Mock
    private ValidationExecutionStore store;
    @Mock
    private ExecutionCodeGenerator generator;
    @Mock
    private JudgeClient client;
    @Mock
    private JudgeResultEvaluator evaluator;
    @Mock
    private Clock clock;
    private ValidationTestExecutor executor;
    private ValidationHeartbeat heartbeat;
    private final JudgeRuntime runtime = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    private final ValidationLease lease
        = new ValidationLease(
            new ProblemValidationRequest(2001, 1001, "java", "a".repeat(64)),
            "lease",
            1,
            "trace",
            LocalDateTime.of(2026, 10, 31, 9, 10)
        );
    private ProblemValidationDataResponse data;
    private final JudgeExecutionResultResponse raw
        = new JudgeExecutionResultResponse(State.COMPLETED, 3, "2", null, null, null, null, null, 0, null);
    private final JudgeTestResult result
        = new JudgeTestResult(State.COMPLETED, JudgeVerdict.WA, 1, null, "2", null, null);

    /** 생성기와 실행기의 경계를 검증할 고정 입력을 준비한다. */
    @BeforeEach
    void prepare() {
        executor = new ValidationTestExecutor(store, List.of(generator), client, evaluator);
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(Instant.parse("2026-10-31T00:00:00Z"));
        org.mockito.Mockito.lenient().when(store.active(lease)).thenReturn(true);
        heartbeat = new ValidationHeartbeat(lease, store, clock);
        var spec = new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null));
        data
            = new ProblemValidationDataResponse(
                "2001",
                "1001",
                "java",
                "a".repeat(64),
                "user-code",
                spec,
                new ProblemValidationDataResponse.Limits(2000, 7000, 262144, 64),
                List.of(new ProblemValidationDataResponse.TestCase("501", false, Map.of(), "3"))
            );
        given(generator.language()).willReturn(JudgeLanguage.JAVA);
        given(generator.generate(spec, "user-code", Map.of()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "wrapped", "stdin"));
        given(store.beginCreate(lease, 1)).willReturn(true);
    }

    /**
     * 일반 실패도 완료 결과로 저장하며 원본 테스트의 기대값으로 판정한다.
     *
     * @throws Exception 실행 실패
     */
    @Test
    @DisplayName("고정 환경으로 생성한 token의 오답 결과를 저장한다")
    void savesCompletedResult() throws Exception {
        created();
        given(client.find("token")).willReturn(raw);
        given(evaluator.evaluate(raw, data.functionSpec().returnType(), "3", 262144, 64)).willReturn(result);
        given(store.saveResult(lease, 1, result)).willReturn(true);
        assertThat(executor.execute(heartbeat, runtime, data, 1)).contains(result);
        verify(client)
            .create(
                new com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest(
                    "java",
                    runtime,
                    "wrapped",
                    "stdin",
                    2000,
                    7000,
                    262144
                )
            );
    }

    /**
     * 생성 실패에서는 새 실행을 반복하지 않는다.
     *
     * @throws Exception 실행 실패
     */
    @Test
    @DisplayName("생성 응답 유실은 UNKNOWN을 보존하고 재생성하지 않는다")
    void preservesUnknown() throws Exception {
        given(client.create(any())).willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED));
        assertThat(executor.execute(heartbeat, runtime, data, 1)).isEmpty();
        verify(client, times(1)).create(any());
        verify(client, never()).find(any());
        verify(store, never()).fail(any(), any());
    }

    /**
     * 생성 후 기한이 지난 작업은 결과 조회를 시작하지 않는다.
     *
     * @throws Exception 실행 실패
     */
    @Test
    @DisplayName("생성 응답 이후 소유권을 잃으면 추가 조회를 하지 않는다")
    void stopsBeforePolling() throws Exception {
        given(client.create(any())).willReturn(new JudgeExecutionCreateResponse("token", runtime));
        given(store.saveToken(lease, 1, "token")).willReturn(true);
        given(store.active(lease)).willReturn(true, true, false);
        assertThat(executor.execute(heartbeat, runtime, data, 1)).isEmpty();
        verify(client, never()).find(any());
    }

    /**
     * 결과 조회 실패는 같은 token 조회로만 재시도한다.
     *
     * @throws Exception 실행 실패
     */
    @Test
    @DisplayName("조회 장애 뒤에도 같은 token을 조회하고 새 실행을 만들지 않는다")
    void retriesOnlyPolling() throws Exception {
        created();
        given(client.find("token"))
            .willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED))
            .willReturn(raw);
        given(evaluator.evaluate(raw, data.functionSpec().returnType(), "3", 262144, 64)).willReturn(result);
        given(store.saveResult(lease, 1, result)).willReturn(true);
        assertThat(executor.execute(heartbeat, runtime, data, 1)).contains(result);
        verify(client, times(1)).create(any());
        verify(client, times(2)).find("token");
    }

    /**
     * 결과를 가져온 뒤 소유권이 바뀌면 완료 결과를 상위 집계에 전달하지 않는다.
     *
     * @throws Exception 실행 실패
     */
    @Test
    @DisplayName("저장 시 소유권이 달라진 늦은 결과는 버린다")
    void dropsLateResult() throws Exception {
        created();
        given(client.find("token")).willReturn(raw);
        given(evaluator.evaluate(raw, data.functionSpec().returnType(), "3", 262144, 64)).willReturn(result);
        given(store.saveResult(lease, 1, result)).willReturn(false);
        assertThat(executor.execute(heartbeat, runtime, data, 1)).isEmpty();
    }

    /**
     * 짧은 테스트가 반복되어도 작업 전체의 갱신 시각을 유지한다.
     *
     * @throws Exception 실행 실패
     */
    @Test
    @DisplayName("20초 테스트 10개를 연속 실행해도 120초 임대가 만료되지 않는다")
    void renewsAcrossShortTests() throws Exception {
        var instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-31T00:00:00Z"));
        var expiry = new java.util.concurrent.atomic.AtomicReference<>(instant.get().plusSeconds(120));
        given(clock.instant()).willAnswer(call -> instant.get());
        given(store.active(lease)).willAnswer(call -> instant.get().isBefore(expiry.get()));
        given(store.heartbeat(lease)).willAnswer(call -> {
            if (!instant.get().isBefore(expiry.get())) {
                return false;
            }
            expiry.set(instant.get().plusSeconds(120));
            return true;
        });
        given(client.create(any())).willReturn(new JudgeExecutionCreateResponse("token", runtime));
        given(store.saveToken(lease, 1, "token")).willReturn(true);
        given(client.find("token")).willAnswer(call -> {
            instant.set(instant.get().plusSeconds(20));
            return raw;
        });
        given(evaluator.evaluate(raw, data.functionSpec().returnType(), "3", 262144, 64)).willReturn(result);
        given(store.saveResult(lease, 1, result)).willReturn(true);
        for (int index = 0; index < 10; index++) {
            assertThat(executor.execute(heartbeat, runtime, data, 1)).contains(result);
        }
        verify(store, org.mockito.Mockito.atLeast(4)).heartbeat(lease);
        assertThat(instant.get()).isBefore(expiry.get());
        verify(client, times(10)).create(any());
    }

    /** 생성 성공 이후의 공통 외부 경계를 준비한다. */
    private void created() {
        given(client.create(any())).willReturn(new JudgeExecutionCreateResponse("token", runtime));
        given(store.saveToken(lease, 1, "token")).willReturn(true);
        given(store.active(lease)).willReturn(true);
    }
}
