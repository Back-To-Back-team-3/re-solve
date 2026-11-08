package com.backtoback.study.operation.dto;

import com.backtoback.study.membership.domain.MembershipRole;

import jakarta.validation.constraints.NotNull;

/**
 * 운영진 역할 변경 요청 바디 (API 명세서 §5.3.2 요청 바디).
 */
public record StudyRoleChangeRequest(@NotNull MembershipRole role) {
}
