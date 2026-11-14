package com.backtoback.study.discovery.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.replica.domain.MemberReplica;

/**
 * 스터디 상세 조회 응답 (API 명세서 5.1.2).
 */
public record StudyDetailResponse(
    String studyId,
    String name,
    String description,
    String goal,
    String skillLevel,
    String preferredLanguage,
    String activeTimeSlot,
    int capacity,
    int memberCount,
    StudyStatus status,
    JoinType joinType,
    boolean isAutoReopen,
    Long version,
    Leader leader,
    MyMembership myMembership,
    OffsetDateTime createdAt
) {

    // 날짜·시각은 Asia/Seoul 기준 ISO 8601(+09:00)로 응답한다(API 명세서 0.1). DB의 LocalDateTime은
    // 이미 Asia/Seoul 기준 벽시계 값이라 오프셋만 붙인다.
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);

    public static StudyDetailResponse of(Study study, Leader leader, MyMembership myMembership) {
        return new StudyDetailResponse(
            String.valueOf(study.getId()),
            study.getName(),
            study.getDescription(),
            study.getGoal(),
            study.getSkillLevel(),
            study.getPreferredLanguage(),
            study.getActiveTimeSlot(),
            study.getCapacity(),
            study.getMemberCount(),
            study.getStatus(),
            study.getJoinType(),
            study.isAutoReopen(),
            study.getVersion(),
            leader,
            myMembership,
            toSeoulOffset(study.getCreatedAt())
        );
    }

    private static OffsetDateTime toSeoulOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(SEOUL_OFFSET);
    }

    /**
     * 스터디장 표시 정보 (API 명세서 5.1.2 leader). 리더를 찾지 못하면(복제본 미동기화 등) {@code null}이다.
     */
    public record Leader(String memberId, String nickname) {

        public static Leader from(MemberReplica replica) {
            return new Leader(String.valueOf(replica.getMemberId()), replica.getNickname());
        }
    }

    /**
     * 요청자의 진행 중 가입 상태 (API 명세서 5.1.2 myMembership). 비로그인이거나 가입 이력이 없으면 전체가 {@code null}이다.
     */
    public record MyMembership(String membershipId, MembershipStatus status, MembershipRole role) {

        public static MyMembership from(StudyMembership membership) {
            return new MyMembership(String.valueOf(membership.getId()), membership.getStatus(), membership.getRole());
        }
    }
}
