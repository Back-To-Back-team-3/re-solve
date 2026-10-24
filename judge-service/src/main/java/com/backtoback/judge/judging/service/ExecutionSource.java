package com.backtoback.judge.judging.service;

import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.dto.request.JudgeExecutionCreateRequest;

/**
 * 테스트 한 건의 실행 언어·전체 코드·입력이다. 언어별 내부 입력 형식은 각 생성기가 결정한다.
 *
 * @param language 생성한 코드의 실행 언어
 * @param sourceCode 실행 템플릿을 포함한 전체 코드
 * @param stdin 해당 언어의 실행 템플릿에서 복원할 입력
 */
public record ExecutionSource(JudgeLanguage language, String sourceCode, String stdin) {
    /**
     * 실행 결과의 언어가 누락되지 않도록 검사한다.
     *
     * @param language 생성한 코드의 실행 언어
     * @param sourceCode 실행 템플릿을 포함한 전체 코드
     * @param stdin 실행 템플릿에서 복원할 입력
     * @throws IllegalArgumentException 실행 언어가 없는 경우
     */
    public ExecutionSource {
        if (language == null) {
            throw new IllegalArgumentException("실행 언어가 필요합니다.");
        }
    }

    /**
     * 생성 결과를 기존 실행 요청에 연결한다. 고정 환경을 전달하면 생성 코드와 언어가 같아야 한다.
     *
     * @param runtime 고정 실행 환경; 최초 실행이면 null
     * @param cpuTimeMs CPU 제한 시간
     * @param wallTimeMs 경과 시간 제한
     * @param memoryKiB 메모리 제한
     * @return 생성 언어와 코드·입력·제한값을 보존한 실행 요청
     * @throws IllegalArgumentException 고정 환경의 언어가 다르거나 코드·제한값이 유효하지 않은 경우
     */
    public JudgeExecutionCreateRequest toRequest(JudgeRuntime runtime, int cpuTimeMs, int wallTimeMs, int memoryKiB) {
        if (runtime != null && !language.getCode().equals(runtime.language())) {
            throw new IllegalArgumentException("생성 코드와 같은 언어의 실행 환경이 필요합니다.");
        }
        return new JudgeExecutionCreateRequest(
            language.getCode(),
            runtime,
            sourceCode,
            stdin,
            cpuTimeMs,
            wallTimeMs,
            memoryKiB
        );
    }
}
