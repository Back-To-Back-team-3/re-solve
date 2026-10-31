package com.backtoback.judge.judging.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 원문이나 내부 실행 이력을 포함하지 않는 검증 진행 응답이다. (API §3.3.6)
 *
 * @param validationId 검증 ID
 * @param problemRevisionId 회차 ID
 * @param language 언어
 * @param status 작업 상태
 * @param tests 현재 시도의 테스트별 결과
 * @param maxTimeMs 최종 시도 최대 CPU 시간
 * @param message 정제한 실패 설명
 * @param runtimeKey 통과 실행 환경 키
 * @param runnerVersion 통과 생성기 참고 정보
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ValidationProgressResponse(
    String validationId,
    String problemRevisionId,
    String language,
    String status,
    List<TestResult> tests,
    Integer maxTimeMs,
    String message,
    String runtimeKey,
    String runnerVersion
) {
    /**
     * 조회 서비스가 현재 시도 기준으로 정리한 결과를 내부 API 응답으로 변환한다.
     *
     * @param validationId 검증 ID
     * @param problemRevisionId 회차 ID
     * @param language 언어
     * @param status 작업 상태
     * @param tests 공개 가능한 현재 시도 결과
     * @param maxTimeMs 최종 시도 최대 시간
     * @param message 정제한 설명
     * @param runtimeKey 통과 실행 환경 키
     * @param runnerVersion 통과 생성기 정보
     * @return 테스트 목록을 복사한 응답
     */
    public static ValidationProgressResponse from(
        String validationId,
        String problemRevisionId,
        String language,
        String status,
        List<TestResult> tests,
        Integer maxTimeMs,
        String message,
        String runtimeKey,
        String runnerVersion
    ) {
        return new ValidationProgressResponse(
            validationId,
            problemRevisionId,
            language,
            status,
            List.copyOf(tests),
            maxTimeMs,
            message,
            runtimeKey,
            runnerVersion
        );
    }

    /**
     * 원문 없이 고정 순서와 현재 시도의 판정·측정값만 반환한다.
     *
     * @param testOrder 고정 테스트 순서
     * @param verdict 테스트 판정; 미완료 또는 이전 시도이면 null
     * @param timeMs CPU 시간; 측정되지 않았으면 null
     * @param memoryKb 메모리; 측정되지 않았으면 null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TestResult(int testOrder, String verdict, Integer timeMs, Integer memoryKb) {
    }
}
