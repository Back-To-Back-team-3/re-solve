package com.backtoback.study.membership.dto;

import com.backtoback.study.membership.domain.MembershipStatus;

import jakarta.validation.constraints.NotNull;

/**
 * 승인·거절·강제탈퇴 PATCH 요청 DTO (API 명세서 §5.2.4).
 * {@code REMOVED}이면 {@code reason}이 필수이며, 서비스 레이어에서 검증한다.
 */
public record MembershipDecisionRequest(@NotNull MembershipStatus status, String reason) {
}
