package com.backtoback.study.discovery.dto;

import java.time.LocalDateTime;

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
    LocalDateTime joinedAt
) {

    public static StudyMySummaryResponse from(StudyMembership membership, Study study) {
        return new StudyMySummaryResponse(
            String.valueOf(study.getId()),
            study.getName(),
            study.getStatus(),
            String.valueOf(membership.getId()),
            membership.getStatus(),
            membership.getRole(),
            membership.getJoinedAt()
        );
    }
}
