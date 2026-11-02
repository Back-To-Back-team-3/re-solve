package com.backtoback.study.membership.dto;

/**
 * 가입 신청·즉시 가입 응답 바디 (API 명세서 §5.2.2 응답).
 * {@code expiresAt}은 INSTANT 가입이면 {@code null}이다.
 */
public record StudyJoinResponse(String membershipId, String status, String expiresAt) {
}
