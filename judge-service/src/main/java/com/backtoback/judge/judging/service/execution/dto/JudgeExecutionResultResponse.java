package com.backtoback.judge.judging.service.execution.dto;

import java.math.BigDecimal;

/**
 * 실행 엔진의 원시 결과를 보관하며 최종 정답 판정은 상위 실행기에 맡긴다.
 *
 * @param state 실행 진행 상태
 * @param statusId Judge0 상태 코드
 * @param stdout 표준 출력
 * @param stderr 표준 오류
 * @param compileOutput 컴파일 출력
 * @param message 엔진 메시지
 * @param timeSeconds 실행 시간(초)
 * @param memoryKiB 사용 메모리(KiB)
 * @param exitCode 프로세스 종료 코드
 * @param exitSignal 프로세스 종료 신호
 */
public record JudgeExecutionResultResponse(
    State state,
    int statusId,
    String stdout,
    String stderr,
    String compileOutput,
    String message,
    BigDecimal timeSeconds,
    BigDecimal memoryKiB,
    Integer exitCode,
    Integer exitSignal
) {
    // COMPLETED는 실행 종료를 의미하며 AC/WA 판정은 상위 실행기가 반환값 비교 후 결정한다.
    public enum State {
        QUEUED, RUNNING, COMPLETED, SYSTEM_ERROR;

        /**
         * Judge0 상태 코드에 대응하는 실행 진행 상태를 조회한다.
         *
         * @param statusId Judge0 상태 코드
         * @return 대응 상태; 알려지지 않은 코드는 {@code SYSTEM_ERROR}
         */
        public static State fromStatusId(int statusId) {
            return switch (statusId) {
                case 1 -> QUEUED;
                case 2 -> RUNNING;
                case 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 -> COMPLETED;
                default -> SYSTEM_ERROR;
            };
        }
    }
}
