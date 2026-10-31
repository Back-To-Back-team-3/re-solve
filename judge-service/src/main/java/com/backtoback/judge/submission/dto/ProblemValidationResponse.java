package com.backtoback.judge.submission.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 고정 회차의 접수 조건이다. (API 명세서 §2.6.1)
 *
 * @param problemId 문제 ID
 * @param problemRevisionId 회차 ID
 * @param scope 문제 용도
 * @param visibility 공개 상태
 * @param difficulty 난이도
 * @param tagIds 문제 태그
 * @param totalCount 테스트 수
 * @param testAssetIds 고정 파일 ID
 * @param supportedLanguages 통과 언어
 * @param executionProfiles 통과 프로필
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemValidationResponse(
    String problemId,
    String problemRevisionId,
    String scope,
    String visibility,
    Integer difficulty,
    List<String> tagIds,
    Integer totalCount,
    List<String> testAssetIds,
    List<String> supportedLanguages,
    List<ProblemExecutionProfileResponse> executionProfiles
) {
}
