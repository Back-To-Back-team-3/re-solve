package com.backtoback.member.admin.dto;

import com.backtoback.member.member.domain.Member;

/**
 * 회원 권한 변경 응답 (API 명세서 1.6.3).
 */
public record MemberRoleChangeResponse(String memberId, String role) {

    public static MemberRoleChangeResponse from(Member member) {
        return new MemberRoleChangeResponse(String.valueOf(member.getId()), member.getRole().name());
    }
}
