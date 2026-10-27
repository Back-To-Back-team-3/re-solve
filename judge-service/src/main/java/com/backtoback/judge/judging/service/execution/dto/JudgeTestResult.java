package com.backtoback.judge.judging.service.execution.dto;

import java.math.BigDecimal;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;

/**
 * 실행 결과를 해석한 내부 테스트 결과다. 공개 여부에 따른 입력·출력 제거는 응답을 만드는 호출자가 담당한다.
 *
 * @param state 실행 진행 상태 또는 시스템 실패
 * @param verdict 테스트 판정; 대기·실행 중이면 {@code null}
 * @param timeMs 올림한 실행 시간(ms); 측정값이 없으면 {@code null}
 * @param memoryKiB 측정 메모리(KiB); 소수 정밀도를 보존하며 측정값이 없으면 {@code null}
 * @param actualReturnText 반환 JSON; 시스템 실패·진행 중·반환값 출력 초과이면 {@code null}
 * @param debugOutput 디버그 출력; 시스템 실패·진행 중·디버그 출력 초과이면 {@code null}
 * @param compileMessage 컴파일 실패 메시지; 컴파일 실패가 아니면 {@code null}
 */
public record JudgeTestResult(
    State state,
    JudgeVerdict verdict,
    Integer timeMs,
    BigDecimal memoryKiB,
    String actualReturnText,
    String debugOutput,
    String compileMessage
) {
}
