package com.backtoback.member.member.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.member.domain.Member;

/**
 * 내 정보 조회 응답 (API 명세서 1.2.1). ID는 문자열로 내려준다.
 */
public record MemberResponse(
    String memberId,
    String githubLogin,
    String nickname,
    String profileImageUrl,
    String email,
    String role,
    String status,
    boolean hasLearningProfile,
    OffsetDateTime createdAt
) {

    public static MemberResponse from(Member member, boolean hasLearningProfile) {
        return new MemberResponse(
            String.valueOf(member.getId()),
            member.getGithubLogin(),
            member.getNickname(),
            member.getProfileImageUrl(),
            member.getEmail(),
            member.getRole().name(),
            member.getStatus().name(),
            hasLearningProfile,
            member.getCreatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}
