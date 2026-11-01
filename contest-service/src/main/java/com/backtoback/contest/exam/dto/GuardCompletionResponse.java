package com.backtoback.contest.exam.dto;

/**
 * 자원 작업 종결 결과. sourceVersion은 10진 문자열이다. (API 명세서 §4.7.4)
 */
public record GuardCompletionResponse(String operationId, String state, String operationResult, String sourceVersion) {
}
