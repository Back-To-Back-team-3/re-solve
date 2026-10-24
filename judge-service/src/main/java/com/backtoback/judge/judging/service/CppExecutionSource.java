package com.backtoback.judge.judging.service;

import com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest;

/**
 * 테스트 한 건의 C++ 실행 코드와 입력이다. 기대값은 실행 환경에 전달하지 않는다.
 *
 * @param sourceCode 사용자 코드와 실행용 {@code main}
 * @param stdin 생성된 {@code main}에서 읽는 입력
 */
public record CppExecutionSource(String sourceCode, String stdin) {
    /**
     * 생성 결과를 기존 Judge0 실행 요청 모델에 연결한다. 제한값은 고정 회차에서 가져온다.
     *
     * @param runtime 고정 실행 환경; 최초 실행이면 {@code null}
     * @param cpuTimeMs CPU 제한 시간
     * @param wallTimeMs 경과 시간 제한
     * @param memoryKiB 메모리 제한
     * @return C++ 실행 요청
     * @throws IllegalArgumentException 다른 언어의 환경 또는 유효하지 않은 제한값인 경우
     */
    public JudgeExecutionCreateRequest toRequest(JudgeRuntime runtime, int cpuTimeMs, int wallTimeMs, int memoryKiB) {
        if (runtime != null && !"cpp".equals(runtime.language())) {
            throw new IllegalArgumentException("C++ 실행 환경이 필요합니다.");
        }
        return new JudgeExecutionCreateRequest("cpp", runtime, sourceCode, stdin, cpuTimeMs, wallTimeMs, memoryKiB);
    }
}
