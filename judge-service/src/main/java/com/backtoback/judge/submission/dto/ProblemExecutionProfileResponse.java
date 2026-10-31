package com.backtoback.judge.submission.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 통과 언어의 실행 환경 키와 참고 정보다.
 *
 * @param language 통과 언어
 * @param runtimeKey 실행 환경 해시
 * @param runnerVersion 생성기 참고 정보
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemExecutionProfileResponse(String language, String runtimeKey, String runnerVersion) {
}
