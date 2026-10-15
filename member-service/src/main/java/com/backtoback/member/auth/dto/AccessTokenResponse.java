package com.backtoback.member.auth.dto;

import com.backtoback.member.auth.service.TokenRefreshResult;

/**
 * 토큰 재발급 응답 (API 명세서 1.1.4).
 */
public record AccessTokenResponse(String accessToken, long expiresIn) {

    public static AccessTokenResponse from(TokenRefreshResult result) {
        return new AccessTokenResponse(result.accessToken(), result.expiresIn());
    }
}
