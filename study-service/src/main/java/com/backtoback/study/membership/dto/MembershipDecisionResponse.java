package com.backtoback.study.membership.dto;

/**
 * 승인·거절·강제탈퇴 PATCH 응답 DTO (API 명세서 §5.2.4).
 */
public record MembershipDecisionResponse(String membershipId, String status) {
}
