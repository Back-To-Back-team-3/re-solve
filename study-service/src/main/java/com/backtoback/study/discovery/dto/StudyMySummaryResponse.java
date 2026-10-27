package com.backtoback.study.discovery.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 내 스터디 목록 항목 응답 (API 명세서 5.1.3).
 */
public record StudyMySummaryResponse(
    String studyId,
    String name,
    StudyStatus studyStatus,
    String membershipId,
    MembershipStatus membershipStatus,
    MembershipRole role,
    OffsetDateTime joinedAt
) {

    // 날짜·시각은 Asia/Seoul 기준 ISO 8601(+09:00)로 응답한다(API 명세서 0.1). DB의 LocalDateTime은
    // 이미 Asia/Seoul 기준 벽시계 값이라 오프셋만 붙인다.
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);

    public static StudyMySummaryResponse from(StudyMembership membership, Study study) {
        return new StudyMySummaryResponse(
            String.valueOf(study.getId()),
            study.getName(),
            study.getStatus(),
            String.valueOf(membership.getId()),
            membership.getStatus(),
            membership.getRole(),
            toSeoulOffset(membership.getJoinedAt())
        );
    }

    private static OffsetDateTime toSeoulOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(SEOUL_OFFSET);
    }
}
