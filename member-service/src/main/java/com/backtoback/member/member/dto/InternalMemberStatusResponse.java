package com.backtoback.member.member.dto;

import com.backtoback.member.member.domain.Member;

/**
 * 내부 회원 상태 조회 응답 (API 명세서 1.7.1). 내부 API라 공통 응답으로 감싸지 않는다(API 명세서 0.3).
 */
public record InternalMemberStatusResponse(String memberId, String status, String role) {

    public static InternalMemberStatusResponse from(Member member) {
        return new InternalMemberStatusResponse(
            String.valueOf(member.getId()),
            member.getStatus().name(),
            member.getRole().name()
        );
    }
}
