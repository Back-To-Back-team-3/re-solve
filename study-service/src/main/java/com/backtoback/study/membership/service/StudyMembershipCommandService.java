package com.backtoback.study.membership.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
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
    private static final DateTimeFormatter EVENT_TIME_FORMAT
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final String AGGREGATE_TYPE = "study_membership";
    private static final String JOINED_EVENT_TYPE = "StudyMemberJoined";
    private static final String LEFT_EVENT_TYPE = "StudyMemberLeft";
    private static final String DECIDED_EVENT_TYPE = "StudyApplicationDecided";
    private static final String SCHEMA_VERSION = "v1";

    private final MemberReplicaRepository memberReplicaRepository;
    private final StudyRepository studyRepository;
    private final StudyMembershipRepository membershipRepository;
    private final StudyMembershipHistoryRepository historyRepository;
    private final StudyMemberQuotaRepository quotaRepository;
    private final AssignmentEnrollmentService enrollmentService;
    private final OutboxEventWriter outboxEventWriter;
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
        try {
            membershipRepository.save(membership);
        } catch (DataIntegrityViolationException e) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_APPLIED);
        }
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
        try {
            membershipRepository.save(membership);
        } catch (DataIntegrityViolationException e) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_APPLIED);
        }
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

        String joinedAt = now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT);
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                JOINED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(membership.getId()),
                correlationId,
                new StudyMemberJoinedPayload(
                    String.valueOf(study.getId()),
                    String.valueOf(membership.getId()),
                    String.valueOf(memberId),
                    MembershipRole.MEMBER.name(),
                    joinedAt
                )
            );

        return new StudyJoinResponse(String.valueOf(membership.getId()), MembershipStatus.APPROVED.name(), null);
    }

    /**
     * 가입 신청 취소(PENDING→CANCELED) 또는 탈퇴(APPROVED→LEFT)를 처리한다(API 명세서 §5.2.3).
     * LEFT 전이이면 quota→studies 순서로 잠금을 획득하고 S2 이벤트를 발행한다.
     *
     * @throws StudyException STUDY_MEMBERSHIP_NOT_FOUND, STUDY_LEADER_DELEGATION_REQUIRED
     */
    @Transactional
    public void cancelOrLeave(Long studyId, Long memberId, String correlationId) {
        StudyMembership membership
            = membershipRepository
                .findByStudyIdAndMemberIdAndStatusIn(
                    studyId,
                    memberId,
                    List.of(MembershipStatus.PENDING, MembershipStatus.APPROVED)
                )
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));

        if (membership.getRole() == MembershipRole.LEADER) {
            throw new StudyException(StudyErrorCode.STUDY_LEADER_DELEGATION_REQUIRED);
        }

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        if (membership.getStatus() == MembershipStatus.PENDING) {
            applyCancel(membership, memberId, now);
        } else {
            applyLeave(membership, studyId, memberId, correlationId, now);
        }
    }

    private void applyCancel(StudyMembership membership, Long memberId, LocalDateTime now) {
        if (membershipRepository.cancelIfPending(membership.getId()) == 0) {
            membershipRepository
                .findById(membership.getId())
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));
            throw new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND);
        }
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membership.getId(),
                        MembershipStatus.PENDING,
                        MembershipStatus.CANCELED,
                        null,
                        membership.getRole(),
                        memberId,
                        null,
                        now
                    )
            );
    }

    /**
     * 가입 신청을 승인한다(PENDING→APPROVED, API 명세서 §5.2.4 승인 4단계).
     *
     * @throws StudyException STUDY_MEMBERSHIP_NOT_FOUND, AUTH_ACCESS_DENIED,
     *     STUDY_APPLICATION_EXPIRED, STUDY_MEMBERSHIP_STATUS_CONFLICT,
     *     STUDY_JOIN_LIMIT_EXCEEDED, STUDY_ALREADY_CLOSED, STUDY_FULL
     */
    @Transactional
    public void approve(Long studyId, Long membershipId, Long requestMemberId, String correlationId) {
        verifyLeaderOrManager(studyId, requestMemberId);

        StudyMembership target
            = membershipRepository
                .findById(membershipId)
                .filter(m -> m.getStudyId().equals(studyId))
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));

        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        applyApprove(study, target, requestMemberId, correlationId, now);
    }

    /**
     * 가입 신청을 거절한다(PENDING→REJECTED, API 명세서 §5.2.4 거절).
     *
     * @throws StudyException STUDY_MEMBERSHIP_NOT_FOUND, AUTH_ACCESS_DENIED,
     *     STUDY_APPLICATION_EXPIRED, STUDY_MEMBERSHIP_STATUS_CONFLICT
     */
    @Transactional
    public void reject(Long studyId, Long membershipId, Long requestMemberId, String correlationId) {
        verifyLeaderOrManager(studyId, requestMemberId);

        StudyMembership target
            = membershipRepository
                .findById(membershipId)
                .filter(m -> m.getStudyId().equals(studyId))
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));

        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        applyReject(study, target, requestMemberId, correlationId, now);
    }

    /**
     * 구성원을 강제탈퇴한다(APPROVED→REMOVED, API 명세서 §5.2.4 강제탈퇴).
     * MANAGER는 LEADER·MANAGER를 대상으로 삼을 수 없다.
     *
     * @throws StudyException STUDY_MEMBERSHIP_NOT_FOUND, AUTH_ACCESS_DENIED, STUDY_NOT_FOUND
     */
    @Transactional
    public void remove(Long studyId, Long membershipId, Long requestMemberId, String reason, String correlationId) {
        StudyMembership requester = verifyLeaderOrManager(studyId, requestMemberId);

        StudyMembership target
            = membershipRepository
                .findById(membershipId)
                .filter(m -> m.getStudyId().equals(studyId))
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));

        if (requester.getRole() == MembershipRole.MANAGER
            && (target.getRole() == MembershipRole.LEADER || target.getRole() == MembershipRole.MANAGER)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        applyRemove(studyId, target, requestMemberId, reason, correlationId, now);
    }

    private StudyMembership verifyLeaderOrManager(Long studyId, Long requestMemberId) {
        StudyMembership requester
            = membershipRepository
                .findByStudyIdAndMemberIdAndStatusIn(studyId, requestMemberId, List.of(MembershipStatus.APPROVED))
                .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        if (requester.getRole() != MembershipRole.LEADER && requester.getRole() != MembershipRole.MANAGER) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return requester;
    }

    private void applyApprove(
        Study study,
        StudyMembership target,
        Long requestMemberId,
        String correlationId,
        LocalDateTime now
    ) {
        // ① membership: PENDING + 만료 안 됨 조건부 UPDATE
        if (membershipRepository.approveIfPending(target.getId(), now) == 0) {
            StudyMembership refreshed
                = membershipRepository
                    .findById(target.getId())
                    .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));
            if (refreshed.getStatus() != MembershipStatus.PENDING) {
                throw new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_STATUS_CONFLICT);
            }
            throw new StudyException(StudyErrorCode.STUDY_APPLICATION_EXPIRED);
        }

        // ② quota
        quotaRepository.insertQuotaIfAbsent(target.getMemberId());
        if (quotaRepository.incrementIfBelowLimit(target.getMemberId()) == 0) {
            throw new StudyException(StudyErrorCode.STUDY_JOIN_LIMIT_EXCEEDED);
        }

        // ③ study: CLOSED 아니고 정원 남은 경우 증가
        if (studyRepository.approveIfNotFull(study.getId()) == 0) {
            Study refreshed
                = studyRepository
                    .findById(study.getId())
                    .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
            if (refreshed.getStatus() == StudyStatus.CLOSED) {
                throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
            }
            throw new StudyException(StudyErrorCode.STUDY_FULL);
        }

        // ④ 문제집 편입
        enrollmentService.enroll(study.getId(), target.getMemberId(), target.getId());

        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        target.getId(),
                        MembershipStatus.PENDING,
                        MembershipStatus.APPROVED,
                        target.getRole(),
                        target.getRole(),
                        requestMemberId,
                        null,
                        now
                    )
            );

        String decidedAt = now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT);
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                JOINED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(target.getId()),
                correlationId,
                new StudyMemberJoinedPayload(
                    String.valueOf(study.getId()),
                    String.valueOf(target.getId()),
                    String.valueOf(target.getMemberId()),
                    target.getRole().name(),
                    decidedAt
                )
            );
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                DECIDED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(target.getId()),
                correlationId,
                new StudyApplicationDecidedPayload(
                    String.valueOf(study.getId()),
                    study.getName(),
                    String.valueOf(target.getId()),
                    String.valueOf(target.getMemberId()),
                    MembershipStatus.APPROVED.name(),
                    decidedAt
                )
            );
    }

    private void applyReject(
        Study study,
        StudyMembership target,
        Long requestMemberId,
        String correlationId,
        LocalDateTime now
    ) {
        if (membershipRepository.rejectIfPending(target.getId()) == 0) {
            StudyMembership refreshed
                = membershipRepository
                    .findById(target.getId())
                    .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));
            if (refreshed.getStatus() != MembershipStatus.PENDING) {
                throw new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_STATUS_CONFLICT);
            }
            throw new StudyException(StudyErrorCode.STUDY_APPLICATION_EXPIRED);
        }
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        target.getId(),
                        MembershipStatus.PENDING,
                        MembershipStatus.REJECTED,
                        target.getRole(),
                        target.getRole(),
                        requestMemberId,
                        null,
                        now
                    )
            );

        String decidedAt = now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT);
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                DECIDED_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(target.getId()),
                correlationId,
                new StudyApplicationDecidedPayload(
                    String.valueOf(study.getId()),
                    study.getName(),
                    String.valueOf(target.getId()),
                    String.valueOf(target.getMemberId()),
                    MembershipStatus.REJECTED.name(),
                    decidedAt
                )
            );
    }

    private void applyRemove(
        Long studyId,
        StudyMembership target,
        Long requestMemberId,
        String reason,
        String correlationId,
        LocalDateTime now
    ) {
        // ① quota 먼저
        quotaRepository.decrementIfAboveZero(target.getMemberId());

        // ② study: CLOSED이면 생략
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.getStatus() != StudyStatus.CLOSED) {
            studyRepository.leaveAndAutoReopen(studyId);
        }

        // ③ membership
        if (membershipRepository.removeIfApproved(target.getId(), reason, now) == 0) {
            membershipRepository
                .findById(target.getId())
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));
            throw new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND);
        }

        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        target.getId(),
                        MembershipStatus.APPROVED,
                        MembershipStatus.REMOVED,
                        target.getRole(),
                        target.getRole(),
                        requestMemberId,
                        reason,
                        now
                    )
            );

        String leftAt = now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT);
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                LEFT_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(target.getId()),
                correlationId,
                new StudyMemberLeftPayload(
                    String.valueOf(studyId),
                    String.valueOf(target.getId()),
                    String.valueOf(target.getMemberId()),
                    MembershipStatus.REMOVED.name(),
                    leftAt,
                    study.getName()
                )
            );
    }

    private void applyLeave(
        StudyMembership membership,
        Long studyId,
        Long memberId,
        String correlationId,
        LocalDateTime now
    ) {
        // ① quota 먼저
        quotaRepository.decrementIfAboveZero(memberId);

        // ② studies 나중 — CLOSED이면 생략
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.getStatus() != StudyStatus.CLOSED) {
            studyRepository.leaveAndAutoReopen(studyId);
        }

        if (membershipRepository.leaveIfApproved(membership.getId(), now) == 0) {
            membershipRepository
                .findById(membership.getId())
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));
            throw new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND);
        }

        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membership.getId(),
                        MembershipStatus.APPROVED,
                        MembershipStatus.LEFT,
                        null,
                        membership.getRole(),
                        memberId,
                        null,
                        now
                    )
            );

        String leftAt = now.atOffset(ZoneOffset.of("+09:00")).format(EVENT_TIME_FORMAT);
        outboxEventWriter
            .append(
                AGGREGATE_TYPE,
                LEFT_EVENT_TYPE,
                SCHEMA_VERSION,
                String.valueOf(membership.getId()),
                correlationId,
                new StudyMemberLeftPayload(
                    String.valueOf(studyId),
                    String.valueOf(membership.getId()),
                    String.valueOf(memberId),
                    MembershipStatus.LEFT.name(),
                    leftAt,
                    study.getName()
                )
            );
    }
}
