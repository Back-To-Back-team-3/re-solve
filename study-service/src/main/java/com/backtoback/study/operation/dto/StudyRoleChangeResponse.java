package com.backtoback.study.operation.dto;

/**
 * 운영진 역할 변경 응답 바디 (API 명세서 §5.3.2 응답 바디).
 */
public record StudyRoleChangeResponse(String membershipId, String role) {
}
