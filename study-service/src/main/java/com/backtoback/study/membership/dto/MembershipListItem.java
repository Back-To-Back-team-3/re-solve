package com.backtoback.study.membership.dto;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.replica.domain.MemberReplica;

/**
 * 구성원·대기 신청 목록 조회 응답 항목(API 명세서 §5.2.4).
 * APPROVED 조회이면 {@code learningProfile}과 {@code expiresAt}은 {@code null}이다.
 */
public record MembershipListItem(
    String membershipId,
    String memberId,
    String nickname,
    String role,
    String status,
    LearningProfileSnapshot learningProfile,
    String appliedAt,
    String expiresAt,
    String joinedAt
) {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    /**
     * APPROVED 목록 항목을 만든다. learningProfile·expiresAt은 null이다.
     */
    public static MembershipListItem ofApproved(StudyMembership membership, MemberReplica replica) {
        return new MembershipListItem(
            String.valueOf(membership.getId()),
            String.valueOf(membership.getMemberId()),
            replica != null ? replica.getNickname() : null,
            membership.getRole().name(),
            membership.getStatus().name(),
            null,
            membership.getAppliedAt().atOffset(ZoneOffset.of("+09:00")).format(FORMATTER),
            null,
            membership.getJoinedAt() != null
                ? membership.getJoinedAt().atOffset(ZoneOffset.of("+09:00")).format(FORMATTER) : null
        );
    }

    /**
     * PENDING 목록 항목을 만든다. learningProfile은 복제본이 없으면 null이다.
     */
    public static MembershipListItem ofPending(
        StudyMembership membership,
        MemberReplica replica,
        LearningProfileSnapshot profile
    ) {
        return new MembershipListItem(
            String.valueOf(membership.getId()),
            String.valueOf(membership.getMemberId()),
            replica != null ? replica.getNickname() : null,
            membership.getRole().name(),
            membership.getStatus().name(),
            profile,
            membership.getAppliedAt().atOffset(ZoneOffset.of("+09:00")).format(FORMATTER),
            membership.getExpiresAt() != null
                ? membership.getExpiresAt().atOffset(ZoneOffset.of("+09:00")).format(FORMATTER) : null,
            null
        );
    }
}
