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
    Long studyId,
    String name,
    StudyStatus studyStatus,
    Long membershipId,
    MembershipStatus membershipStatus,
    MembershipRole role,
    LocalDateTime joinedAt
) {

    public static StudyMySummaryResponse from(StudyMembership membership, Study study) {
        return new StudyMySummaryResponse(
            study.getId(),
            study.getName(),
            study.getStatus(),
            membership.getId(),
            membership.getStatus(),
            membership.getRole(),
            membership.getJoinedAt()
        );
    }
}
