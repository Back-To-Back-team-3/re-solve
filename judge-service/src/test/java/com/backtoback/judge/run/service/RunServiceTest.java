package com.backtoback.judge.run.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.client.TestAssetClient;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse.Limits;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.Asset;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestCase;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestContent;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.JudgeClient;
import com.backtoback.judge.judging.service.execution.JudgeResultEvaluator;
import com.backtoback.judge.judging.service.execution.JudgeReturnComparator;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionCreateResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.python.PythonExecutionCodeGenerator;
import com.backtoback.judge.run.dto.RunRequest;
import com.backtoback.judge.run.dto.RunRequest.CustomInput;
import com.backtoback.judge.run.store.RunLockStore;
import com.backtoback.judge.run.store.RunLockStore.Lease;
import com.backtoback.judge.submission.service.SubmissionAccessService;

/** 외부 의존을 대체해 공개 범위와 실행 결과, 제한 해제의 경계를 검증한다. */
class RunServiceTest {
    private final RunLockStore locks = mock(RunLockStore.class);
    private final SubmissionAccessService access = mock(SubmissionAccessService.class);
    private final SubmissionJudgingDataClient data = mock(SubmissionJudgingDataClient.class);
    private final TestAssetClient assets = mock(TestAssetClient.class);
    private final JudgeClient client = mock(JudgeClient.class);
    private final Lease lease = new Lease(7L, "token", "instance", "owner", 1L);

    /** 숨김 테스트 파일을 읽지 않고 공개 예제와 직접 입력을 구분하는지 확인한다. */
    @Test
    @DisplayName("공개 예제만 읽고 직접 입력의 기대값과 판정은 비워 둔다")
    void runsOnlyVisibleCasesAndCustomInputs() throws Exception {
        var sample = new Asset("sample", "1", "a".repeat(64));
        var hidden = new Asset("hidden", "1", "b".repeat(64));
        prepare(List.of(new TestCase("1", 1, false, hidden), new TestCase("2", 2, true, sample)));
        when(assets.read(sample)).thenReturn(new TestContent(Map.of("a", "1"), "2"));
        when(client.find("judge-token")).thenReturn(result(3, "2", "debug"), result(3, "4", null));

        try (
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var heartbeat = Executors.newSingleThreadScheduledExecutor()
        ) {
            var service
                = new RunService(
                    locks,
                    access,
                    data,
                    assets,
                    List.of(new PythonExecutionCodeGenerator()),
                    client,
                    new JudgeResultEvaluator(new JudgeReturnComparator(), client),
                    executor,
                    heartbeat
                );
            var response = service.run(7L, request("3"));
            assertThat(response.results()).satisfiesExactly(value -> {
                assertThat(value.testAssetId()).isEqualTo("2");
                assertThat(value.expectedReturnText()).isEqualTo("2");
                assertThat(value.verdict()).isEqualTo(JudgeVerdict.AC);
                assertThat(value.stdout()).isEqualTo("debug");
                assertThat(value.timeMs()).isEqualTo(2);
            }, value -> {
                assertThat(value.index()).isZero();
                assertThat(value.verdict()).isNull();
                assertThat(value.expectedReturnText()).isNull();
                assertThat(value.actualReturnText()).isEqualTo("4");
            });
        }
        verify(assets).read(sample);
        org.mockito.Mockito.verifyNoMoreInteractions(assets);
        verify(locks).release(lease);
    }

    /** 타입 오류가 엔진 호출이나 파일 원문 읽기로 이어지지 않게 한다. */
    @Test
    @DisplayName("직접 입력 타입이 틀리면 엔진 호출 없이 제한을 해제한다")
    void rejectsWrongInputBeforeAnyExecution() {
        prepare(List.of());
        try (
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var heartbeat = Executors.newSingleThreadScheduledExecutor()
        ) {
            var service
                = new RunService(
                    locks,
                    access,
                    data,
                    assets,
                    List.of(new PythonExecutionCodeGenerator()),
                    client,
                    new JudgeResultEvaluator(new JudgeReturnComparator(), client),
                    executor,
                    heartbeat
                );
            assertThatThrownBy(() -> service.run(7L, request("true")))
                .isInstanceOfSatisfying(
                    JudgeException.class,
                    failure -> assertThat(failure.getErrorCode().getCode()).isEqualTo("COMMON_INVALID_REQUEST")
                );
        }
        verifyNoInteractions(assets);
        verify(client, org.mockito.Mockito.never()).create(any());
        verify(locks).release(lease);
    }

    /** 회원 접근 오류를 엔진 호출 전에 반환하고 사용자별 제한을 풀어 준다. */
    @Test
    @DisplayName("정지 회원은 실행하지 않고 획득한 제한을 해제한다")
    void deniesSuspendedMember() {
        when(locks.acquire(7L)).thenReturn(lease);
        when(access.runRuntime(7L, 301L, 1001L, "python3"))
            .thenThrow(new JudgeException(JudgeErrorCode.MEMBER_SUSPENDED));
        try (
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var heartbeat = Executors.newSingleThreadScheduledExecutor()
        ) {
            var service
                = new RunService(
                    locks,
                    access,
                    data,
                    assets,
                    List.of(new PythonExecutionCodeGenerator()),
                    client,
                    new JudgeResultEvaluator(new JudgeReturnComparator(), client),
                    executor,
                    heartbeat
                );
            assertThatThrownBy(() -> service.run(7L, request("3")))
                .isInstanceOfSatisfying(
                    JudgeException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(JudgeErrorCode.MEMBER_SUSPENDED)
                );
        }
        verifyNoInteractions(data, assets, client);
        verify(locks).release(lease);
    }

    /** 고정 회차와 통과한 런타임만 반환하도록 준비한다. */
    private void prepare(List<TestCase> cases) {
        var runtime = new JudgeRuntime("python3", 71, "Python", "3.11", null);
        var int32 = new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null);
        var function = new FunctionSpec("solution", List.of(new FunctionSpec.Parameter("a", int32)), int32);
        when(locks.acquire(7L)).thenReturn(lease);
        when(access.runRuntime(7L, 301L, 1001L, "python3")).thenReturn(runtime);
        when(data.find(301L, 1001L))
            .thenReturn(
                new SubmissionJudgingDataResponse(
                    "301",
                    "1001",
                    "더하기",
                    1,
                    List.of("배열"),
                    List.of("1"),
                    function,
                    new Limits(2000, 7000, 8000, 64),
                    cases,
                    List.of()
                )
            );
        when(client.create(any())).thenReturn(new JudgeExecutionCreateResponse("judge-token", runtime));
    }

    /** 공개 예제와 매개변수 하나의 직접 입력을 요청한다. */
    private RunRequest request(String text) {
        return new RunRequest(
            "301",
            "1001",
            "python3",
            "def solution(a):\n    return a + 1\n",
            true,
            List.of(new CustomInput(Map.of("a", text)))
        );
    }

    /** 반환값과 디버그 출력이 다른 스트림으로 오는 종료 응답을 준비한다. */
    private JudgeExecutionResultResponse result(int status, String output, String debug) {
        return new JudgeExecutionResultResponse(
            State.COMPLETED,
            status,
            output,
            debug,
            null,
            null,
            new BigDecimal("0.0015"),
            BigDecimal.TEN,
            0,
            null
        );
    }
}
