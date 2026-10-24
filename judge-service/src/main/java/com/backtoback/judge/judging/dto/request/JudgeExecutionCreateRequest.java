package com.backtoback.judge.judging.dto.request;

import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/**
 * 실행 엔진에 전달할 코드·입력과 실행 환경 및 자원 제한을 보관한다.
 *
 * @param language 서비스 언어 코드
 * @param fixedRuntime 고정 실행 환경; null이면 최신 환경을 선택한다
 * @param sourceCode 실행할 전체 코드
 * @param stdin 표준 입력
 * @param cpuTimeMs CPU 제한 시간(ms)
 * @param wallTimeMs 경과 시간 제한(ms)
 * @param memoryKiB 메모리 제한(KiB)
 */
public record JudgeExecutionCreateRequest(
    String language,
    JudgeRuntime fixedRuntime,
    String sourceCode,
    String stdin,
    int cpuTimeMs,
    int wallTimeMs,
    int memoryKiB
) {
    /**
     * 실행기가 준비한 전체 실행 코드·입력과 고정된 자원 제한을 보관한다.
     * 함수 래퍼 생성과 기본 제한값 적용은 호출자가 수행한다.
     *
     * @param language 서비스 언어 코드
     * @param fixedRuntime 사용할 고정 실행 환경; null이면 지원 범위의 최신 환경을 선택한다
     * @param sourceCode 래퍼를 포함한 실행 코드
     * @param stdin 표준 입력; 입력이 없으면 null 또는 빈 문자열
     * @param cpuTimeMs CPU 제한 시간(ms)
     * @param wallTimeMs 실제 경과 시간 제한(ms)
     * @param memoryKiB 메모리 제한(KiB)
     * @throws IllegalArgumentException 코드·제한값이 잘못된 경우
     */
    public JudgeExecutionCreateRequest {
        if (sourceCode == null || sourceCode.isBlank() || cpuTimeMs <= 0 || wallTimeMs < cpuTimeMs || memoryKiB <= 0) {
            throw new IllegalArgumentException("Execution code and positive, consistent limits are required");
        }
    }
}
