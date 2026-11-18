package com.backtoback.study.assignment.dto;

/**
 * 로그인 회원 기준 배정 문제 레벨 잠금 예외 확인 응답 (API 명세서 5.4.5).
 * 명세의 ID 표기에 맞춰 {@code problemId}를 문자열로 내려준다.
 */
public record AssignedProblemResponse(String problemId, boolean assigned) {

    public static AssignedProblemResponse of(Long problemId, boolean assigned) {
        return new AssignedProblemResponse(String.valueOf(problemId), assigned);
    }
}
