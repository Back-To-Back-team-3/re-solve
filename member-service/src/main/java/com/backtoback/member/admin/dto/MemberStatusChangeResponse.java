package com.backtoback.member.admin.dto;

import com.backtoback.member.member.domain.Member;

/**
 * 회원 정지·해제 응답 (API 명세서 1.6.2).
 */
public record MemberStatusChangeResponse(String memberId, String status) {

    public static MemberStatusChangeResponse from(Member member) {
        return new MemberStatusChangeResponse(String.valueOf(member.getId()), member.getStatus().name());
    }
}
