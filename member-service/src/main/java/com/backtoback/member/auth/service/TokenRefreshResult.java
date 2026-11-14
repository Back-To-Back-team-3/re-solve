package com.backtoback.member.auth.service;

/**
 * 토큰 재발급 결과.
 *
 * @param newRefreshToken 새로 교체한 Refresh Token. 유예 시간 안의 중복 재발급이면 {@code null}이며, 이때는 쿠키를 바꾸지 않는다.
 */
public record TokenRefreshResult(String accessToken, long expiresIn, String newRefreshToken) {

    public boolean refreshTokenRotated() {
        return newRefreshToken != null;
    }
}
