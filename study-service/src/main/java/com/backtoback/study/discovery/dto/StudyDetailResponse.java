package com.backtoback.study.discovery.dto;

import java.time.LocalDateTime;

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
    Long studyId,
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
    LocalDateTime createdAt
) {

    public static StudyDetailResponse of(Study study, Leader leader, MyMembership myMembership) {
        return new StudyDetailResponse(
            study.getId(),
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
            study.getCreatedAt()
        );
    }

    /**
     * 스터디장 표시 정보 (API 명세서 5.1.2 leader). 리더를 찾지 못하면(복제본 미동기화 등) {@code null}이다.
     */
    public record Leader(Long memberId, String nickname) {

        public static Leader from(MemberReplica replica) {
            return new Leader(replica.getMemberId(), replica.getNickname());
        }
    }

    /**
     * 요청자의 진행 중 가입 상태 (API 명세서 5.1.2 myMembership). 비로그인이거나 가입 이력이 없으면 전체가 {@code null}이다.
     */
    public record MyMembership(Long membershipId, MembershipStatus status, MembershipRole role) {

        public static MyMembership from(StudyMembership membership) {
            return new MyMembership(membership.getId(), membership.getStatus(), membership.getRole());
        }
    }
}
