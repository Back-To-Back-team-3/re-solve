package com.backtoback.gateway.auth;

/**
 * JWT 검증 후 추출된 사용자 식별 정보.
 */
public record UserPrincipal(String memberId, String role) {
}
