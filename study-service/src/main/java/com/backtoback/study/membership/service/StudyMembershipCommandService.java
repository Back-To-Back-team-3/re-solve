package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.assignment.service.AssignmentEnrollmentService;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyMembershipHistory;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.dto.StudyJoinResponse;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 가입·취소·탈퇴 명령 서비스 (API 명세서 §5.2.2·§5.2.3).
 */
@Service
@RequiredArgsConstructor
public class StudyMembershipCommandService {

    private static final int PENDING_EXPIRE_DAYS = 7;
    private static final DateTimeFormatter EXPIRES_AT_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    private final MemberReplicaRepository memberReplicaRepository;
    private final StudyRepository studyRepository;
    private final StudyMembershipRepository membershipRepository;
    private final StudyMembershipHistoryRepository historyRepository;
    private final StudyMemberQuotaRepository quotaRepository;
    private final AssignmentEnrollmentService enrollmentService;
    private final Clock clock;

    /**
     * 가입 신청(APPROVAL → PENDING) 또는 즉시 가입(INSTANT → APPROVED)을 처리한다(API 명세서 §5.2.2).
     * INSTANT 가입이면 같은 트랜잭션에서 문제집 편입을 수행한다.
     *
     * @throws StudyException STUDY_NOT_FOUND, STUDY_NOT_RECRUITING, STUDY_ALREADY_APPLIED,
     *     STUDY_JOIN_LIMIT_EXCEEDED, STUDY_FULL, MEMBER_SUSPENDED
     */
    @Transactional
    public StudyJoinResponse join(Long studyId, Long memberId, String correlationId) {
        memberReplicaRepository.findByMemberId(memberId).ifPresent(replica -> {
            if (replica.getMemberStatus() == MemberStatus.SUSPENDED) {
                throw new StudyException(StudyErrorCode.MEMBER_SUSPENDED);
            }
        });

        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));

        if (study.getStatus() != StudyStatus.RECRUITING) {
            throw new StudyException(StudyErrorCode.STUDY_NOT_RECRUITING);
        }

        // 중복 신청 사전 확인(uk_study_memberships_study_active_member 유일 제약 보완)
        if (membershipRepository
            .findByStudyIdAndMemberIdAndStatusIn(
                studyId,
                memberId,
                List.of(MembershipStatus.PENDING, MembershipStatus.APPROVED)
            )
            .isPresent()) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_APPLIED);
        }

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        if (study.getJoinType() == JoinType.APPROVAL) {
            return applyApproval(study, memberId, now);
        }
        return applyInstant(study, memberId, correlationId, now);
    }

    private StudyJoinResponse applyApproval(Study study, Long memberId, LocalDateTime now) {
        LocalDateTime expiresAt = now.plusDays(PENDING_EXPIRE_DAYS);
        StudyMembership membership
            = StudyMembership
                .apply(study.getId(), memberId, MembershipRole.MEMBER, MembershipStatus.PENDING, now, expiresAt, null);
        membershipRepository.save(membership);
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membership.getId(),
                        null,
                        MembershipStatus.PENDING,
                        null,
                        MembershipRole.MEMBER,
                        memberId,
                        null,
                        now
                    )
            );
        String expiresAtStr = expiresAt.atOffset(ZoneOffset.of("+09:00")).format(EXPIRES_AT_FORMAT);
        return new StudyJoinResponse(String.valueOf(membership.getId()), MembershipStatus.PENDING.name(), expiresAtStr);
    }

    private StudyJoinResponse applyInstant(Study study, Long memberId, String correlationId, LocalDateTime now) {
        // ① quota 먼저: 행 보장 후 카운터 증가(ERD 4.5.4, API 명세서 §5.2.2 동시성 보장)
        quotaRepository.insertQuotaIfAbsent(memberId);
        if (quotaRepository.incrementIfBelowLimit(memberId) == 0) {
            throw new StudyException(StudyErrorCode.STUDY_JOIN_LIMIT_EXCEEDED);
        }

        // ② studies: 정원·상태 조건부 업데이트
        if (studyRepository.joinInstantIfRecruiting(study.getId()) == 0) {
            Study refreshed
                = studyRepository
                    .findById(study.getId())
                    .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
            if (refreshed.getStatus() != StudyStatus.RECRUITING) {
                throw new StudyException(StudyErrorCode.STUDY_NOT_RECRUITING);
            }
            throw new StudyException(StudyErrorCode.STUDY_FULL);
        }

        StudyMembership membership
            = StudyMembership
                .apply(study.getId(), memberId, MembershipRole.MEMBER, MembershipStatus.APPROVED, now, null, now);
        membershipRepository.save(membership);
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membership.getId(),
                        null,
                        MembershipStatus.APPROVED,
                        null,
                        MembershipRole.MEMBER,
                        memberId,
                        null,
                        now
                    )
            );

        enrollmentService.enroll(study.getId(), memberId, membership.getId());

        return new StudyJoinResponse(String.valueOf(membership.getId()), MembershipStatus.APPROVED.name(), null);
    }
}
