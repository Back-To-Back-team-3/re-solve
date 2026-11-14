package com.backtoback.member.auth.service;

import com.backtoback.member.member.domain.Member;

/**
 * 로그인 코드 교환 결과. Refresh Token은 응답 본문이 아니라 쿠키로 내려간다.
 */
public record LoginResult(String accessToken, long expiresIn, String refreshToken, Member member, boolean newMember) {
}
