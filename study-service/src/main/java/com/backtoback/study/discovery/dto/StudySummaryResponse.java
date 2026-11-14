package com.backtoback.study.discovery.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 스터디 목록 조회 응답 (API 명세서 5.1.1).
 */
public record StudySummaryResponse(
    String studyId,
    String name,
    String goal,
    String skillLevel,
    String preferredLanguage,
    String activeTimeSlot,
    int capacity,
    int memberCount,
    StudyStatus status,
    JoinType joinType,
    OffsetDateTime lastActivityAt,
    OffsetDateTime createdAt
) {

    // 날짜·시각은 Asia/Seoul 기준 ISO 8601(+09:00)로 응답한다(API 명세서 0.1). DB의 LocalDateTime은
    // 이미 Asia/Seoul 기준 벽시계 값이라 오프셋만 붙인다.
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);

    public static StudySummaryResponse from(Study study) {
        return new StudySummaryResponse(
            String.valueOf(study.getId()),
            study.getName(),
            study.getGoal(),
            study.getSkillLevel(),
            study.getPreferredLanguage(),
            study.getActiveTimeSlot(),
            study.getCapacity(),
            study.getMemberCount(),
            study.getStatus(),
            study.getJoinType(),
            toSeoulOffset(study.getLastActivityAt()),
            toSeoulOffset(study.getCreatedAt())
        );
    }

    private static OffsetDateTime toSeoulOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(SEOUL_OFFSET);
    }
}
