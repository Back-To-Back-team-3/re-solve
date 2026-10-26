package com.backtoback.judge.judging.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.dto.TypeSpec.Kind;
import com.backtoback.judge.judging.service.JudgeClient;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;

/** 엔진 상태·자원 근거·개별 출력 제한과 정상 반환값 비교의 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class JudgeResultEvaluatorTest {
    private static final int MEMORY_LIMIT = 262144;
    private static final TypeSpec INT = new TypeSpec(Kind.INT32, null, null, null, null);
    private static final TypeSpec STRING = new TypeSpec(Kind.STRING, null, null, null, null);
    @Mock
    private JudgeClient client;
    private JudgeResultEvaluator evaluator;

    /** 실제 비교기와 한 번만 호출할 실행 클라이언트로 판정기를 구성한다. */
    @BeforeEach
    void setUp() {
        evaluator = new JudgeResultEvaluator(new JudgeReturnComparator(), client);
    }

    /**
     * Accepted라도 실제 반환값의 타입과 기대값을 별도로 비교한다.
     *
     * @param actual 실제 반환 JSON
     * @param verdict 기대 판정
     */
    @ParameterizedTest
    @CsvSource(
        value = {
            "1|AC",
            "2|WA",
            "\"1\"|WA",
            "null|WA",
            "1 2|WA"
        },
        delimiter = '|'
    )
    @DisplayName("Judge0 Accepted를 곧바로 AC로 바꾸지 않고 반환값을 비교한다")
    void comparesAcceptedReturn(String actual, JudgeVerdict verdict) {
        var result = evaluate(execution(3, actual, "debug", "0.0015", "8192"));
        assertThat(result.state()).isEqualTo(State.COMPLETED);
        assertThat(result.verdict()).isEqualTo(verdict);
        assertThat(result.timeMs()).isEqualTo(2);
        assertThat(result.memoryKiB()).isEqualByComparingTo("8192");
        assertThat(result.actualReturnText()).isEqualTo(actual);
        assertThat(result.debugOutput()).isEqualTo("debug");
        assertThat(result.compileMessage()).isNull();
    }

    /**
     * 엔진의 실패 결과를 반환값 비교 없이 사용자 판정으로 변환한다.
     *
     * @param status Judge0 종료 상태
     * @param verdict 기대 판정
     */
    @ParameterizedTest
    @CsvSource(
        {
            "4,WA",
            "5,TLE",
            "6,CE",
            "7,RE",
            "8,RE",
            "9,RE",
            "10,RE",
            "11,RE",
            "12,RE"
        }
    )
    @DisplayName("Judge0 실패 상태는 기대값과 출력이 같아도 해당 실패 판정을 유지한다")
    void mapsExecutionFailures(int status, JudgeVerdict verdict) {
        var result = evaluate(execution(status, "1", "", null, null));
        assertThat(result.verdict()).isEqualTo(verdict);
        assertThat(result.compileMessage()).isEqualTo(status == 6 ? "compile-message" : null);
    }

    /**
     * 런타임 오류 신호만으로 메모리 초과를 추정하지 않는다.
     *
     * @param memory 측정 메모리; null은 측정값 없음
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
            "0",
            "262143",
            "262143.999"
        }
    )
    @DisplayName("메모리 초과 측정 근거가 없으면 SIGSEGV 계열도 RE로 유지한다")
    void doesNotGuessMemoryFailure(String memory) {
        var result = evaluate(execution(7, "", "out of memory", "1", memory));
        assertThat(result.verdict()).isEqualTo(JudgeVerdict.RE);
    }

    /**
     * 메모리 제한과 같은 측정값부터 MLE를 적용한다.
     *
     * @param status Accepted·WA 또는 런타임 오류 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            3,
            4,
            7,
            8,
            9,
            10,
            11,
            12
        }
    )
    @DisplayName("실측 메모리가 한도 이상이면 정상 종료와 런타임 오류를 MLE로 보정한다")
    void appliesMeasuredMemoryLimit(int status) {
        assertThat(evaluate(execution(status, "1", "", "0.1", "262144")).verdict()).isEqualTo(JudgeVerdict.MLE);
        assertThat(evaluate(execution(status, "1", "", "0.1", "262144.001")).verdict()).isEqualTo(JudgeVerdict.MLE);
    }

    /**
     * 컴파일 실패와 시간 초과를 실행 메모리로 덮어쓰지 않는다.
     *
     * @param status 엔진 실패 상태
     * @param verdict 기대 판정
     */
    @ParameterizedTest
    @CsvSource(
        {
            "5,TLE",
            "6,CE"
        }
    )
    @DisplayName("시간·컴파일 실패는 메모리 측정값으로 MLE로 바꾸지 않는다")
    void preservesEngineFailures(int status, JudgeVerdict verdict) {
        var raw = execution(status, "1", "debug", "1", "300000");
        assertThat(evaluate(raw).verdict()).isEqualTo(verdict);
    }

    /**
     * 종료한 실행의 출력 제한 초과는 엔진 상태와 별개로 정책의 RE를 적용한다.
     *
     * @param status 엔진 종료 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12
        }
    )
    @DisplayName("종료한 실행에서 개별 출력 제한을 초과하면 정책대로 RE로 처리한다")
    void appliesOutputFailurePolicy(int status) {
        var raw = execution(status, "1", "x".repeat(65537), "1", "100");
        assertThat(evaluate(raw).verdict()).isEqualTo(JudgeVerdict.RE);
    }

    /**
     * 초 단위 측정을 저장·응답용 정수 밀리초로 올림한다.
     *
     * @param seconds 원시 시간
     * @param milliseconds 기대 기록 시간
     */
    @ParameterizedTest
    @CsvSource(
        {
            "0,0",
            "0.001,1",
            "0.0010001,2",
            "0.0015,2",
            "0.0000001,1",
            "2147483.647,2147483647"
        }
    )
    @DisplayName("실행 시간은 정확한 소수 연산으로 밀리초 변환 후 올림한다")
    void roundsTimeForRecording(String seconds, int milliseconds) {
        assertThat(evaluate(execution(3, "1", "", seconds, "8192.25")).timeMs()).isEqualTo(milliseconds);
        assertThat(evaluate(execution(3, "1", "", seconds, "8192.25")).verdict()).isEqualTo(JudgeVerdict.AC);
    }

    /** 측정값이 없으면 0으로 추정하지 않고 null로 전달한다. */
    @Test
    @DisplayName("시간·메모리 미측정은 null로 유지하고 소수 메모리는 원래 정밀도를 보존한다")
    void preservesMissingMeasurements() {
        var missing = evaluate(execution(3, "1", null, null, null));
        assertThat(missing.timeMs()).isNull();
        assertThat(missing.memoryKiB()).isNull();
        assertThat(evaluate(execution(3, "1", "", "0", "8192.25")).memoryKiB()).isEqualByComparingTo("8192.25");
    }

    /** 반환값과 디버그 출력은 각 한도까지 동시에 허용한다. */
    @Test
    @DisplayName("반환 JSON 1MiB와 디버그 64KiB를 합산하지 않고 각각 한도까지 허용한다")
    void appliesSeparateLimits() {
        String json = "\"" + "x".repeat(1024 * 1024 - 2) + "\"";
        var result
            = evaluator.evaluate(execution(3, json, "x".repeat(65536), "1", "100"), STRING, json, MEMORY_LIMIT, 1024);
        assertThat(result.verdict()).isEqualTo(JudgeVerdict.AC);
        assertThat(result.actualReturnText()).isEqualTo(json);
        assertThat(result.debugOutput()).hasSize(65536);
    }

    /** 회차별 반환값 제한은 바이트 단위로 적용하고 초과한 출력을 결과에 보관하지 않는다. */
    @Test
    @DisplayName("회차별 반환값 한도는 JSON 따옴표와 UTF-8 바이트를 포함한다")
    void enforcesRevisionReturnLimit() {
        String exact = "\"" + "x".repeat(1022) + "\"";
        assertThat(evaluator.evaluate(execution(3, exact, "", "0", "0"), STRING, exact, MEMORY_LIMIT, 1).verdict())
            .isEqualTo(JudgeVerdict.AC);
        String exceeded = "\"" + "가".repeat(341) + "\"";
        var result = evaluator.evaluate(execution(3, exceeded, "debug", "0", "0"), STRING, exceeded, MEMORY_LIMIT, 1);
        assertThat(result.verdict()).isEqualTo(JudgeVerdict.RE);
        assertThat(result.actualReturnText()).isNull();
        assertThat(result.debugOutput()).isEqualTo("debug");
        String escaped = "\"" + "\\u0061".repeat(171) + "\"";
        assertThat(
            evaluator
                .evaluate(execution(3, escaped, "", "0", "0"), STRING, "\"" + "a".repeat(171) + "\"", MEMORY_LIMIT, 1)
                .verdict()
        ).isEqualTo(JudgeVerdict.RE);
    }

    /** 디버그 제한은 문제의 반환값 제한과 독립적이며 UTF-8 바이트로 계산한다. */
    @Test
    @DisplayName("디버그 출력이 64KiB를 넘으면 잘라서 AC로 처리하지 않는다")
    void rejectsExcessDebugOutput() {
        assertThat(evaluate(execution(3, "1", "가".repeat(21845), "0", "0")).verdict()).isEqualTo(JudgeVerdict.AC);
        var exceeded = evaluate(execution(3, "1", "가".repeat(21846), "0", "0"));
        assertThat(exceeded.verdict()).isEqualTo(JudgeVerdict.RE);
        assertThat(exceeded.actualReturnText()).isEqualTo("1");
        assertThat(exceeded.debugOutput()).isNull();
    }

    /**
     * 진행 중 결과는 출력이나 임시 측정값으로 최종 판정을 만들지 않는다.
     *
     * @param status 대기 또는 실행 중 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            1,
            2
        }
    )
    @DisplayName("대기·실행 중 결과는 사용자 판정과 출력 없이 진행 상태를 유지한다")
    void preservesPendingState(int status) {
        var result = evaluate(execution(status, "1", "debug", "1", "1"));
        assertThat(result.state()).isEqualTo(status == 1 ? State.QUEUED : State.RUNNING);
        assertThat(result.verdict()).isNull();
        assertThat(result.actualReturnText()).isNull();
        assertThat(result.timeMs()).isNull();
    }

    /**
     * 엔진 시스템 오류는 메모리 근거로 사용자 MLE로 변환하지 않는다.
     *
     * @param status 시스템 오류 또는 지원하지 않는 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            0,
            13,
            14,
            15
        }
    )
    @DisplayName("시스템 오류와 알 수 없는 상태는 출력 원문 없는 SYSTEM_ERROR로 처리한다")
    void mapsSystemStatuses(int status) {
        var result = evaluate(execution(status, "secret", "secret", "1", "300000"));
        assertThat(result.state()).isEqualTo(State.SYSTEM_ERROR);
        assertThat(result.verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
        assertThat(result.actualReturnText()).isNull();
        assertThat(result.debugOutput()).isNull();
        assertThat(result.compileMessage()).isNull();
    }

    /** 일관되지 않거나 저장 가능한 시간 범위를 벗어난 엔진 측정값을 정상 판정에 쓰지 않는다. */
    @Test
    @DisplayName("음수 측정값·시간 정수 범위 초과·상태 불일치는 시스템 오류로 구분한다")
    void rejectsInvalidEngineResults() {
        assertThat(evaluate(execution(3, "1", "", "-1", "1")).verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
        assertThat(evaluate(execution(3, "1", "", "1", "-1")).verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
        assertThat(evaluate(execution(3, "1", "", "2147483.648", "1")).verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
        var inconsistent = new JudgeExecutionResultResponse(State.RUNNING, 3, "1", "", null, null, null, null, 0, null);
        assertThat(evaluate(inconsistent).verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
        assertThat(evaluate(null).verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
    }

    /**
     * Accepted 상태와 모순되는 실제 프로세스 실패도 정상 반환값 비교보다 먼저 확인한다.
     *
     * @param exitCode 프로세스 종료 코드
     * @param exitSignal 프로세스 종료 신호
     */
    @ParameterizedTest
    @CsvSource(
        {
            "1,0",
            "0,11"
        }
    )
    @DisplayName("Accepted라도 종료 코드나 신호가 실패를 가리키면 RE로 처리한다")
    void preservesProcessFailure(int exitCode, int exitSignal) {
        var raw
            = new JudgeExecutionResultResponse(
                State.COMPLETED,
                3,
                "1",
                "",
                null,
                null,
                null,
                null,
                exitCode,
                exitSignal
            );
        assertThat(evaluate(raw).verdict()).isEqualTo(JudgeVerdict.RE);
    }

    /**
     * 조회 장애를 사용자 코드 실패로 바꾸거나 자동 재시도하지 않는다.
     *
     * @param code Judge0 연동 장애 코드
     */
    @ParameterizedTest
    @EnumSource(
        value = JudgeErrorCode.class,
        names = {
            "JUDGE0_CONNECTION_FAILED",
            "JUDGE0_RESPONSE_FAILED",
            "JUDGE0_CONFIGURATION_INVALID"
        }
    )
    @DisplayName("Judge0 조회의 연결·타임아웃·응답·설정 실패는 한 번 호출 후 시스템 오류로 전달한다")
    void mapsClientFailures(JudgeErrorCode code) {
        given(client.find("token")).willThrow(new JudgeException(code));
        var result = evaluator.findAndEvaluate("token", INT, "1", MEMORY_LIMIT, 1024);
        assertThat(result.verdict()).isEqualTo(JudgeVerdict.SYSTEM_ERROR);
        verify(client).find("token");
        verifyNoMoreInteractions(client);
    }

    /** 조회한 결과를 같은 비교·판정 경로로 연결하고 다른 업무 예외는 숨기지 않는다. */
    @Test
    @DisplayName("조회 성공은 반환값 비교로 연결하고 비통신 업무 예외와 기대값 오류는 호출자에게 전달한다")
    void preservesCallerFailures() {
        given(client.find("ok")).willReturn(execution(3, "1", "", "0", "0"));
        assertThat(evaluator.findAndEvaluate("ok", INT, "1", MEMORY_LIMIT, 1024).verdict()).isEqualTo(JudgeVerdict.AC);
        given(client.find("unavailable")).willThrow(new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE));
        assertThatThrownBy(() -> evaluator.findAndEvaluate("unavailable", INT, "1", MEMORY_LIMIT, 1024))
            .isInstanceOf(JudgeException.class);
        assertThatThrownBy(() -> evaluator.findAndEvaluate("ok", INT, "\"invalid\"", MEMORY_LIMIT, 1024))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.findAndEvaluate("unused", INT, "1", MEMORY_LIMIT, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 정수 반환값의 기본 사례를 평가한다.
     *
     * @param raw 원시 결과
     * @return 정수 1을 기대한 판정 결과
     */
    private JudgeTestResult evaluate(JudgeExecutionResultResponse raw) {
        return evaluator.evaluate(raw, INT, "1", MEMORY_LIMIT, 1024);
    }

    /**
     * 상태와 측정값을 지정해 엔진 응답을 만든다.
     *
     * @param status Judge0 상태
     * @param output 반환 JSON
     * @param debug 디버그 출력
     * @param time 실행 시간(초); 미측정이면 null
     * @param memory 메모리(KiB); 미측정이면 null
     * @return 비교·판정에 전달할 원시 응답
     */
    private static JudgeExecutionResultResponse execution(
        int status,
        String output,
        String debug,
        String time,
        String memory
    ) {
        State state = switch (status) {
            case 1 -> State.QUEUED;
            case 2 -> State.RUNNING;
            case 13, 14 -> State.SYSTEM_ERROR;
            default -> State.COMPLETED;
        };
        return new JudgeExecutionResultResponse(
            state,
            status,
            output,
            debug,
            "compile-message",
            "message",
            time == null ? null : new BigDecimal(time),
            memory == null ? null : new BigDecimal(memory),
            0,
            null
        );
    }
}
