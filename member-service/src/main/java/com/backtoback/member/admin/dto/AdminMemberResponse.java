package com.backtoback.member.admin.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.member.domain.Member;

/**
 * 관리자 회원 목록 항목 (API 명세서 1.6.1). ID는 문자열로 내려준다.
 */
public record AdminMemberResponse(
    String memberId,
    String nickname,
    String githubLogin,
    String role,
    String status,
    OffsetDateTime createdAt
) {

    public static AdminMemberResponse from(Member member) {
        return new AdminMemberResponse(
            String.valueOf(member.getId()),
            member.getNickname(),
            member.getGithubLogin(),
            member.getRole().name(),
            member.getStatus().name(),
            member.getCreatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}
