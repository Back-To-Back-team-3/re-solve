package com.backtoback.study.operation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 스터디장 위임 요청 바디 (API 명세서 §5.3.3 요청 바디).
 */
public record LeaderDelegationRequest(@NotNull Long targetMembershipId) {
}
