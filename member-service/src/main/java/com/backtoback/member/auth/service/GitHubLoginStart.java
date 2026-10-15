package com.backtoback.member.auth.service;

/**
 * GitHub 로그인 시작 결과.
 *
 * @param authorizeUrl GitHub 인가 화면 주소
 * @param browserNonce 로그인을 시작한 브라우저에 쿠키로 내려줄 nonce. 콜백·코드 교환에서 이 브라우저인지 확인한다.
 */
public record GitHubLoginStart(String authorizeUrl, String browserNonce) {
}
