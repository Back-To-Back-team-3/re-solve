package com.backtoback.judge.judging.service;

/**
 * 실제 제출에 사용하는 실행 환경을 보관한다.
 *
 * @param language 서비스 언어 코드
 * @param judge0LanguageId Judge0에서 조회한 실행 환경 ID
 * @param runtimeName Judge0가 제공한 실행 환경 이름
 * @param runtimeVersion 실행 환경의 일치 여부를 검증할 실제 숫자 버전
 * @param compilerOptions 제출에 적용할 컴파일 옵션; 없으면 null
 */
public record JudgeRuntime(
    String language,
    int judge0LanguageId,
    String runtimeName,
    String runtimeVersion,
    String compilerOptions
) {
}
