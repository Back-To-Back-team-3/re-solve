package com.backtoback.member.auth.dto;

import com.backtoback.member.auth.service.LoginResult;
import com.backtoback.member.member.domain.Member;

/**
 * 로그인 코드 교환 응답 (API 명세서 1.1.3). ID는 문자열로 내려준다.
 */
public record TokenResponse(String accessToken, long expiresIn, LoginMember member) {

    public static TokenResponse from(LoginResult result) {
        Member member = result.member();
        LoginMember loginMember
            = new LoginMember(
                String.valueOf(member.getId()),
                member.getNickname(),
                member.getRole().name(),
                member.getStatus().name(),
                result.newMember()
            );
        return new TokenResponse(result.accessToken(), result.expiresIn(), loginMember);
    }

    /**
     * @param isNewMember 최초 로그인이면 {@code true} (학습 프로필 안내용)
     */
    public record LoginMember(String memberId, String nickname, String role, String status, boolean isNewMember) {
    }
}
