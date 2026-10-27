package com.backtoback.judge.judging.service.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.service.JudgeClient;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;

import lombok.RequiredArgsConstructor;

/**
 * 기대값이 있는 테스트 한 건의 실행 결과를 판정한다. 재시도·제출 전체 집계·공개 응답 생성은 호출자가 담당한다.
 * (정책 및 상태 §3.2·§3.3)
 */
@Service
@RequiredArgsConstructor
public final class JudgeResultEvaluator {
    private static final int MAX_RETURN_LIMIT_KIB = 256;
    private static final int DEBUG_LIMIT_BYTES = 64 * 1024;
    private final JudgeReturnComparator comparator;
    private final JudgeClient client;

    /**
     * 실행 결과를 한 번 조회하고 통신·응답·설정 장애를 사용자 판정과 구분한다.
     * 대기·실행 중 결과를 다시 조회하거나 새 실행을 만들지 않는다.
     *
     * @param token 기존 실행의 조회 토큰
     * @param returnType 고정 회차의 반환 타입
     * @param expectedText 고정 회차의 기대 JSON 텍스트
     * @param memoryLimitKiB 회차의 메모리 제한(KiB)
     * @param outputLimitKiB 회차의 반환값 제한(KiB, 1~256)
     * @return 실행 진행 상태 또는 테스트 판정
     * @throws JudgeException 통신 장애 이외의 업무 오류가 발생한 경우
     * @throws IllegalArgumentException 제한값·토큰·명세·기대값이 올바르지 않은 경우
     */
    public JudgeTestResult findAndEvaluate(
        String token,
        TypeSpec returnType,
        String expectedText,
        int memoryLimitKiB,
        int outputLimitKiB
    ) {
        requireLimits(memoryLimitKiB, outputLimitKiB);
        JudgeExecutionResultResponse execution;
        try {
            execution = client.find(token);
        } catch (JudgeException exception) {
            if (exception.getErrorCode() == JudgeErrorCode.JUDGE0_CONNECTION_FAILED
                || exception.getErrorCode() == JudgeErrorCode.JUDGE0_RESPONSE_FAILED
                || exception.getErrorCode() == JudgeErrorCode.JUDGE0_CONFIGURATION_INVALID) {
                return systemFailure();
            }
            throw exception;
        }
        return evaluate(execution, returnType, expectedText, memoryLimitKiB, outputLimitKiB);
    }

    /**
     * 실행 상태·자원 측정·개별 출력 제한을 확인하고 정상 실행의 반환값을 비교한다.
     * 메모리 측정값은 임의로 반올림하지 않고 원래 KiB 정밀도를 유지한다.
     *
     * @param execution Judge0의 원시 실행 결과
     * @param returnType 고정 회차의 반환 타입
     * @param expectedText 고정 회차의 기대 JSON 텍스트
     * @param memoryLimitKiB 회차의 메모리 제한(KiB)
     * @param outputLimitKiB 회차의 반환값 제한(KiB, 1~256)
     * @return 대기·실행 중에는 판정이 없는 결과, 종료 시 테스트 판정 또는 시스템 실패
     * @throws IllegalArgumentException 제한값·명세·기대값이 올바르지 않은 경우
     */
    public JudgeTestResult evaluate(
        JudgeExecutionResultResponse execution,
        TypeSpec returnType,
        String expectedText,
        int memoryLimitKiB,
        int outputLimitKiB
    ) {
        requireLimits(memoryLimitKiB, outputLimitKiB);
        if (execution == null || execution.state() != State.fromStatusId(execution.statusId())) {
            return systemFailure();
        }
        if (execution.state() == State.SYSTEM_ERROR) {
            return systemFailure();
        }
        if (execution.state() == State.QUEUED || execution.state() == State.RUNNING) {
            return new JudgeTestResult(execution.state(), null, null, null, null, null, null);
        }
        Integer timeMs;
        try {
            if (execution.timeSeconds() != null && execution.timeSeconds().signum() < 0
                || execution.memoryKiB() != null && execution.memoryKiB().signum() < 0) {
                return systemFailure();
            }
            timeMs
                = execution.timeSeconds() == null ? null
                    : execution.timeSeconds().movePointRight(3).setScale(0, RoundingMode.CEILING).intValueExact();
        } catch (ArithmeticException exception) {
            return systemFailure();
        }
        boolean returnExceeded = exceeds(execution.stdout(), (long)outputLimitKiB * 1024);
        boolean debugExceeded = exceeds(execution.stderr(), DEBUG_LIMIT_BYTES);
        JudgeVerdict verdict
            = verdict(execution, returnType, expectedText, memoryLimitKiB, returnExceeded, debugExceeded);
        return new JudgeTestResult(
            State.COMPLETED,
            verdict,
            timeMs,
            execution.memoryKiB(),
            returnExceeded ? null : execution.stdout(),
            debugExceeded ? null : execution.stderr(),
            verdict == JudgeVerdict.CE ? execution.compileOutput() : null
        );
    }

    /**
     * 엔진의 실행 실패를 먼저 구분하고 정상 종료에만 기대값 비교를 적용한다.
     *
     * @param execution 원시 실행 결과
     * @param type 반환 타입
     * @param expectedText 기대 JSON 텍스트
     * @param memoryLimitKiB 메모리 제한(KiB)
     * @param returnExceeded 반환값 크기 초과 여부
     * @param debugExceeded 디버그 출력 크기 초과 여부
     * @return 사용자 테스트 판정
     * @throws IllegalArgumentException 비교할 명세·기대값이 올바르지 않은 경우
     */
    private JudgeVerdict verdict(
        JudgeExecutionResultResponse execution,
        TypeSpec type,
        String expectedText,
        int memoryLimitKiB,
        boolean returnExceeded,
        boolean debugExceeded
    ) {
        // TLE·CE는 메모리 보정 대상이 아니며, 나머지 종료 상태는 출력 초과보다 실측 메모리를 우선한다.
        if (execution.statusId() != 5 && execution.statusId() != 6 && execution.memoryKiB() != null
            && execution.memoryKiB().compareTo(BigDecimal.valueOf(memoryLimitKiB)) >= 0) {
            return JudgeVerdict.MLE;
        }
        if (returnExceeded || debugExceeded) {
            return JudgeVerdict.RE;
        }
        if (execution.statusId() == 5) {
            return JudgeVerdict.TLE;
        }
        if (execution.statusId() == 6) {
            return JudgeVerdict.CE;
        }
        if (execution.statusId() >= 7 || execution.exitCode() != null && execution.exitCode() != 0
            || execution.exitSignal() != null && execution.exitSignal() != 0) {
            return JudgeVerdict.RE;
        }
        if (execution.statusId() == 4) {
            return JudgeVerdict.WA;
        }
        return comparator.matches(type, expectedText, execution.stdout()) ? JudgeVerdict.AC : JudgeVerdict.WA;
    }

    /**
     * UTF-8 출력의 크기가 한도를 초과하는지 확인한다. 같은 크기는 허용한다.
     *
     * @param text 검사할 원시 출력; {@code null}은 출력 없음
     * @param limitBytes 허용 바이트 수
     * @return 출력 크기가 한도를 초과하는지 여부
     */
    private boolean exceeds(String text, long limitBytes) {
        return text != null && text.getBytes(StandardCharsets.UTF_8).length > limitBytes;
    }

    /**
     * 호출자가 전달한 확정 회차의 메모리 제한이 양수이고 반환값 제한이 허용 범위인지 확인한다.
     *
     * @param memoryLimitKiB 메모리 제한(KiB)
     * @param outputLimitKiB 반환값 제한(KiB, 1~256)
     * @throws IllegalArgumentException 메모리 제한이 양수가 아니거나 반환값 제한이 1~256KiB 범위를 벗어난 경우
     */
    private void requireLimits(int memoryLimitKiB, int outputLimitKiB) {
        if (memoryLimitKiB <= 0 || outputLimitKiB <= 0 || outputLimitKiB > MAX_RETURN_LIMIT_KIB) {
            throw new IllegalArgumentException("회차의 메모리 제한은 양수이고 반환값 제한은 1~256KiB여야 합니다.");
        }
    }

    /**
     * 외부 메시지나 테스트 출력이 노출되지 않는 내부 시스템 실패를 만든다.
     *
     * @return 재시도 정책에 전달할 실패 결과
     */
    private JudgeTestResult systemFailure() {
        return new JudgeTestResult(State.SYSTEM_ERROR, JudgeVerdict.SYSTEM_ERROR, null, null, null, null, null);
    }
}
