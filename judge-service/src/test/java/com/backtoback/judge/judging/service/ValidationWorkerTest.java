package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.client.ProblemValidationDataClient;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.dto.ValidationLease;
import com.backtoback.judge.judging.service.execution.ExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.ValidationExecutionStore;

/** 상위 실행 흐름의 실패 분류와 전체 테스트 실행을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ValidationWorkerTest {
    @Mock
    private ValidationExecutionStore store;
    @Mock
    private ProblemValidationDataClient dataClient;
    @Mock
    private JudgeLanguageService languages;
    @Mock
    private ExecutionCodeGenerator generator;
    @Mock
    private ValidationTestExecutor executor;
    private ValidationWorker worker;
    private final ValidationLease lease
        = new ValidationLease(
            new ProblemValidationRequest(2001, 1001, "java", "a".repeat(64)),
            "token",
            1,
            "original-trace",
            LocalDateTime.of(2026, 10, 31, 9, 10)
        );
    private final JudgeRuntime runtime = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    private ProblemValidationDataResponse data;

    /** 원문을 저장하지 않는 고정 검증 자료를 준비한다. */
    @BeforeEach
    void prepare() {
        worker
            = new ValidationWorker(
                store,
                dataClient,
                languages,
                List.of(generator),
                executor,
                Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ClockConfiguration.SERVICE_ZONE)
            );
        data
            = new ProblemValidationDataResponse(
                "2001",
                "1001",
                "java",
                "a".repeat(64),
                "source",
                new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null)),
                new ProblemValidationDataResponse.Limits(2000, 7000, 262144, 64),
                List
                    .of(
                        new ProblemValidationDataResponse.TestCase("501", true, Map.of(), "1"),
                        new ProblemValidationDataResponse.TestCase("502", false, Map.of(), "1")
                    )
            );
    }

    /** 선점 대상이 없으면 외부 서비스를 호출하지 않는다. */
    @Test
    @DisplayName("빈 대기열에서는 자료 조회나 실행을 하지 않는다")
    void skipsEmptyQueue() {
        given(store.claim()).willReturn(Optional.empty());
        assertThat(worker.runOnce()).isFalse();
        verify(dataClient, never()).find(any());
    }

    /** 오답을 만나도 나머지 테스트를 실행하고 저장된 전체 결과로 종결한다. */
    @Test
    @DisplayName("오답 뒤에도 모든 테스트를 실행하고 같은 heartbeat와 원 요청 추적 정보를 사용한다")
    void completesAllTests() throws Exception {
        ready();
        given(executor.execute(any(), eq(runtime), eq(data), eq(1))).willAnswer(call -> {
            assertThat(MDC.get("correlationId")).isEqualTo("original-trace");
            return result(JudgeVerdict.WA);
        });
        given(executor.execute(any(), eq(runtime), eq(data), eq(2))).willReturn(result(JudgeVerdict.AC));
        MDC.put("correlationId", "caller-trace");
        try {
            assertThat(worker.runOnce()).isTrue();
            var contexts = org.mockito.ArgumentCaptor.forClass(ValidationHeartbeat.class);
            verify(executor, org.mockito.Mockito.times(2)).execute(contexts.capture(), eq(runtime), eq(data), anyInt());
            assertThat(contexts.getAllValues().get(0)).isSameAs(contexts.getAllValues().get(1));
            verify(store).complete(lease, "java-runner-v1");
            verify(store, never()).fail(any(), any());
            assertThat(MDC.get("correlationId")).isEqualTo("caller-trace");
        } finally {
            MDC.clear();
        }
    }

    /** 시스템 오류는 코드 오답으로 종결하지 않는다. */
    @Test
    @DisplayName("시스템 오류는 재시도 정책으로 전달하고 정상 완료하지 않는다")
    void retriesSystemFailure() throws Exception {
        ready();
        given(executor.execute(any(), eq(runtime), eq(data), eq(1))).willReturn(result(JudgeVerdict.SYSTEM_ERROR));
        worker.runOnce();
        verify(store).fail(lease, "java-runner-v1");
        verify(store, never()).complete(any(), any());
        verify(executor, never()).execute(any(), any(), any(), eq(2));
    }

    /** 생성 응답 유실이나 소유권 상실은 자동 재실행하지 않는다. */
    @Test
    @DisplayName("불명확한 실행 결과는 RUNNING을 보존하고 실패 재시도를 예약하지 않는다")
    void preservesUnknown() throws Exception {
        ready();
        given(executor.execute(any(), eq(runtime), eq(data), eq(1))).willReturn(Optional.empty());
        worker.runOnce();
        verify(store, never()).fail(any(), any());
        verify(store, never()).complete(any(), any());
    }

    /** 자료 조회 장애는 실행 전 시스템 실패로 기록한다. */
    @Test
    @DisplayName("자료 조회 실패는 생성기 정보 없이 시스템 실패로 처리한다")
    void failsBeforeExecution() {
        given(store.claim()).willReturn(Optional.of(lease));
        given(store.active(lease)).willReturn(true);
        given(dataClient.find(lease.request())).willThrow(new IllegalStateException("private-data"));
        worker.runOnce();
        verify(store).fail(lease, null);
        assertThat(MDC.get("correlationId")).isNull();
    }

    /** 이미 확정된 환경을 최신 환경으로 교체하지 않는다. */
    @Test
    @DisplayName("재시도는 저장된 실행 환경을 사용한다")
    void reusesFixedRuntime() throws Exception {
        ready();
        given(store.runtime(lease)).willReturn(runtime);
        given(languages.requireRuntime("java", runtime)).willReturn(runtime);
        given(executor.execute(any(), eq(runtime), eq(data), eq(1))).willReturn(Optional.empty());
        worker.runOnce();
        verify(languages).requireRuntime("java", runtime);
    }

    /** 정상 실행 준비까지 연결한다. */
    private void ready() {
        given(store.claim()).willReturn(Optional.of(lease));
        given(store.active(lease)).willReturn(true);
        given(dataClient.find(lease.request())).willReturn(data);
        org.mockito.Mockito.lenient().when(languages.requireRuntime("java", null)).thenReturn(runtime);
        given(store.prepare(lease, data, runtime)).willReturn(true);
        given(generator.language()).willReturn(JudgeLanguage.JAVA);
        given(generator.runnerVersion()).willReturn("java-runner-v1");
    }

    /**
     * 테스트 완료 판정을 만든다.
     *
     * @param verdict 판정
     * @return 완료 결과
     */
    private Optional<JudgeTestResult> result(JudgeVerdict verdict) {
        return Optional.of(new JudgeTestResult(State.COMPLETED, verdict, 1, null, null, null, null));
    }
}
