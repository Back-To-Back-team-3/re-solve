package com.backtoback.study.discovery.dto;

import java.time.LocalDateTime;

import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 스터디 목록 조회 응답 (API 명세서 5.1.1).
 */
public record StudySummaryResponse(
    Long studyId,
    String name,
    String goal,
    String skillLevel,
    String preferredLanguage,
    String activeTimeSlot,
    int capacity,
    int memberCount,
    StudyStatus status,
    JoinType joinType,
    LocalDateTime lastActivityAt,
    LocalDateTime createdAt
) {

    public static StudySummaryResponse from(Study study) {
        return new StudySummaryResponse(
            study.getId(),
            study.getName(),
            study.getGoal(),
            study.getSkillLevel(),
            study.getPreferredLanguage(),
            study.getActiveTimeSlot(),
            study.getCapacity(),
            study.getMemberCount(),
            study.getStatus(),
            study.getJoinType(),
            study.getLastActivityAt(),
            study.getCreatedAt()
        );
    }
}
