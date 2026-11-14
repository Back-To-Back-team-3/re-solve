package com.backtoback.judge.submission.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 초기 결과 자리 생성에 필요한 고정 자료다. 원문·S3 객체는 역직렬화하지 않는다.
 *
 * @param problemId 문제 ID
 * @param problemRevisionId 회차 ID
 * @param supportedLanguages 통과 언어
 * @param executionProfiles 통과 프로필
 * @param cases 고정 테스트 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemJudgingDataResponse(
    String problemId,
    String problemRevisionId,
    List<String> supportedLanguages,
    List<ProblemExecutionProfileResponse> executionProfiles,
    List<TestCase> cases
) {

    /**
     * 테스트의 불변 메타데이터다.
     *
     * @param testAssetId 파일 ID
     * @param testOrder 순번
     * @param isSample 공개 여부
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(String testAssetId, Integer testOrder, Boolean isSample) {
    }
}
