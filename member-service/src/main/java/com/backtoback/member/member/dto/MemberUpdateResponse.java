package com.backtoback.member.member.dto;

import com.backtoback.member.member.domain.Member;

/**
 * 내 정보 수정 응답 (API 명세서 1.2.2).
 */
public record MemberUpdateResponse(String memberId, String nickname) {

    public static MemberUpdateResponse from(Member member) {
        return new MemberUpdateResponse(String.valueOf(member.getId()), member.getNickname());
    }
}
