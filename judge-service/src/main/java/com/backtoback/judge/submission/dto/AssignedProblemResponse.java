package com.backtoback.judge.submission.dto;

import tools.jackson.databind.JsonNode;

/**
 * 회원의 배정 문제 여부를 확인한 Study 내부 응답이다. (API 명세서 §5.7.4)
 *
 * @param memberId 확인한 회원 ID
 * @param problemId 확인한 문제 ID
 * @param assigned 배정 여부. JSON 불리언만 허용한다.
 */
public record AssignedProblemResponse(String memberId, String problemId, JsonNode assigned) {
}
