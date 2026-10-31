package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
    private SubmissionJudgingDataResponse data;
    private final JudgeRuntime runtime = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    private final SubmissionLease lease
        = new SubmissionLease(1, 1, 1, 1, "lease", LocalDateTime.of(2026, 10, 31, 9, 10));

    @BeforeEach
    void prepare() {
        worker
            = new SubmissionWorker(
                store,
                dataClient,
                assets,
                languages,
                List.of(generator),
                client,
                evaluator,
                Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)
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
                        new SubmissionJudgingDataResponse.TestCase("1", 1, true, null),
                        new SubmissionJudgingDataResponse.TestCase("2", 2, false, null)
                    )
            );
        given(store.claim()).willReturn(Optional.of(lease));
        given(store.active(lease)).willReturn(true);
        given(store.input(lease)).willReturn(new SubmissionExecutionInput(1, 1, "java", "code", runtime));
        given(dataClient.find(1, 1)).willReturn(data);
        given(store.prepare(lease)).willReturn(true);
        given(generator.language()).willReturn(JudgeLanguage.JAVA);
        given(assets.read(null)).willReturn(new SubmissionJudgingDataResponse.TestContent(Map.of(), "1"));
        given(generator.generate(any(), eq("code"), any()))
            .willReturn(new ExecutionSource(JudgeLanguage.JAVA, "generated", "stdin"));
        given(store.beginCreate(eq(lease), anyInt())).willReturn(true);
    }

    @Test
    @DisplayName("첫 테스트가 WA여도 전체 테스트를 실행한 뒤 종결한다")
    void executesAll() {
        given(client.create(any())).willAnswer(invocation -> {
            assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo(lease.correlationId());
            return new JudgeExecutionCreateResponse("token", runtime);
        });
        given(store.saveToken(eq(lease), anyInt(), eq("token"))).willReturn(true);
        given(store.saveResult(eq(lease), anyInt(), any())).willReturn(true);
        given(evaluator.evaluate(any(), any(), eq("1"), eq(1024), eq(64)))
            .willReturn(
                new JudgeTestResult(State.COMPLETED, JudgeVerdict.WA, 1, null, "0", null, null),
                new JudgeTestResult(State.COMPLETED, JudgeVerdict.AC, 1, null, "1", null, null)
            );
        assertThat(worker.runOnce()).isTrue();
        verify(client, times(2)).create(any());
        verify(store).complete(lease, data, null);
    }

    @Test
    @DisplayName("생성 응답을 잃으면 새 실행이나 실패 재시도를 만들지 않는다")
    void keepsUnknown() {
        given(client.create(any())).willThrow(new JudgeException(JudgeErrorCode.JUDGE0_CONNECTION_FAILED));
        assertThat(worker.runOnce()).isTrue();
        verify(client, times(1)).create(any());
        verify(store, never()).complete(any(), any(), any());
        verify(store, never()).fail(any(), any());
    }
}
