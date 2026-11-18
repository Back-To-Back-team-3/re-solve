package com.backtoback.study.assignment.dto;

/**
 * 배정 문제 레벨 잠금 예외 확인 내부 응답 (API 명세서 5.7.4).
 * 명세의 ID 표기에 맞춰 {@code memberId}·{@code problemId}를 문자열로 내려준다.
 */
public record AssignedProblemInternalResponse(String memberId, String problemId, boolean assigned) {

    public static AssignedProblemInternalResponse from(Long memberId, Long problemId, boolean assigned) {
        return new AssignedProblemInternalResponse(String.valueOf(memberId), String.valueOf(problemId), assigned);
    }
}
