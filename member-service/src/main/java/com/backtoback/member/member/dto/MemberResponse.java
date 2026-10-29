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

    public static MemberResponse from(Member member) {
        return new MemberResponse(
            String.valueOf(member.getId()),
            member.getGithubLogin(),
            member.getNickname(),
            member.getProfileImageUrl(),
            member.getEmail(),
            member.getRole().name(),
            member.getStatus().name(),
            // shortcut: 학습 프로필(1.3)이 아직 없어 항상 false, learning_profiles를 추가할 때 실제 여부로 바꾼다.
            false,
            member.getCreatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}
