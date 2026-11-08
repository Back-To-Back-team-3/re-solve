package com.backtoback.study.operation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyMembershipHistory;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.operation.dto.LeaderDelegationRequest;
import com.backtoback.study.operation.dto.LeaderDelegationResponse;
import com.backtoback.study.operation.dto.StudyRoleChangeRequest;
import com.backtoback.study.operation.dto.StudyRoleChangeResponse;
import com.backtoback.study.operation.dto.StudyUpdateRequest;
import com.backtoback.study.operation.dto.StudyUpdateResponse;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 운영 명령 서비스 (API 명세서 §5.3).
 */
@Service
@RequiredArgsConstructor
public class StudyOperationService {

    private final StudyRepository studyRepository;
    private final StudyMembershipRepository membershipRepository;
    private final StudyMembershipHistoryRepository historyRepository;
    private final Clock clock;

    /**
     * 스터디 설정을 수정한다(API 명세서 §5.3.1). {@code LEADER}만 호출할 수 있다.
     * null 필드는 변경하지 않는다. 충돌 감지 시 재조회로 원인을 판별한 뒤 적절한 오류를 던진다.
     *
     * @throws StudyException STUDY_NOT_FOUND, AUTH_ACCESS_DENIED, STUDY_PENDING_APPLICATIONS_EXIST,
     *     STUDY_ALREADY_CLOSED, STUDY_UPDATE_CONFLICT, STUDY_CAPACITY_TOO_SMALL
     */
    @Transactional
    public StudyUpdateResponse updateStudy(Long studyId, Long requesterId, StudyUpdateRequest request) {
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));

        StudyMembership leader
            = membershipRepository
                .findByStudyIdAndRoleAndStatus(studyId, MembershipRole.LEADER, MembershipStatus.APPROVED)
                .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        if (!leader.getMemberId().equals(requesterId)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        // APPROVAL → INSTANT 전환 시 처리 대기 중인 신청이 남아 있으면 거부한다(정책 및 상태 §가입 방식 변경).
        if (request.joinType() == JoinType.INSTANT && study.getJoinType() == JoinType.APPROVAL) {
            List<StudyMembership> pending
                = membershipRepository.findAllByStudyIdAndStatus(studyId, MembershipStatus.PENDING);
            if (!pending.isEmpty()) {
                throw new StudyException(StudyErrorCode.STUDY_PENDING_APPLICATIONS_EXIST);
            }
        }

        String effectiveDescription = request.description() != null ? request.description() : study.getDescription();
        JoinType effectiveJoinType = request.joinType() != null ? request.joinType() : study.getJoinType();
        int effectiveCapacity = request.capacity() != null ? request.capacity() : study.getCapacity();
        boolean effectiveAutoReopen = request.isAutoReopen() != null ? request.isAutoReopen() : study.isAutoReopen();

        int rows
            = studyRepository
                .updateSettings(
                    studyId,
                    request.version(),
                    effectiveDescription,
                    effectiveJoinType.name(),
                    effectiveCapacity,
                    effectiveAutoReopen
                );

        if (rows == 0) {
            Study fresh
                = studyRepository
                    .findById(studyId)
                    .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
            if (fresh.getStatus() == StudyStatus.CLOSED) {
                throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
            }
            if (!fresh.getVersion().equals(request.version())) {
                throw new StudyException(StudyErrorCode.STUDY_UPDATE_CONFLICT);
            }
            throw new StudyException(StudyErrorCode.STUDY_CAPACITY_TOO_SMALL);
        }

        return new StudyUpdateResponse(String.valueOf(studyId), request.version() + 1);
    }

    /**
     * 운영진 역할을 변경한다(API 명세서 §5.3.2). {@code LEADER}만 호출할 수 있다.
     * 같은 역할로 요청하면 histories를 기록하지 않고 멱등 응답을 반환한다.
     *
     * @throws StudyException STUDY_NOT_FOUND, STUDY_ALREADY_CLOSED, AUTH_ACCESS_DENIED,
     *     STUDY_MEMBERSHIP_NOT_FOUND, STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED, STUDY_MANAGER_LIMIT_EXCEEDED
     */
    @Transactional
    public StudyRoleChangeResponse changeRole(
        Long studyId,
        Long membershipId,
        Long requesterId,
        StudyRoleChangeRequest request
    ) {
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.getStatus() == StudyStatus.CLOSED) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }

        StudyMembership leader
            = membershipRepository
                .findByStudyIdAndRoleAndStatus(studyId, MembershipRole.LEADER, MembershipStatus.APPROVED)
                .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        if (!leader.getMemberId().equals(requesterId)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }

        StudyMembership target
            = membershipRepository
                .findByIdAndStudyIdAndStatus(membershipId, studyId, MembershipStatus.APPROVED)
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));

        if (target.getRole() == MembershipRole.LEADER) {
            throw new StudyException(StudyErrorCode.STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED);
        }
        if (request.role() == MembershipRole.LEADER) {
            throw new StudyException(StudyErrorCode.STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED);
        }
        if (target.getRole() == request.role()) {
            return new StudyRoleChangeResponse(String.valueOf(membershipId), request.role().name());
        }

        if (request.role() == MembershipRole.MANAGER) {
            int rows = studyRepository.incrementManagerCountIfNotFull(studyId);
            if (rows == 0) {
                throw new StudyException(StudyErrorCode.STUDY_MANAGER_LIMIT_EXCEEDED);
            }
            membershipRepository.updateRole(membershipId, MembershipRole.MANAGER.name());
        } else {
            membershipRepository.updateRole(membershipId, MembershipRole.MEMBER.name());
            studyRepository.decrementManagerCount(studyId);
        }

        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        membershipId,
                        target.getStatus(),
                        target.getStatus(),
                        target.getRole(),
                        request.role(),
                        requesterId,
                        null,
                        LocalDateTime
                            .ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE)
                            .truncatedTo(ChronoUnit.MICROS)
                    )
            );

        return new StudyRoleChangeResponse(String.valueOf(membershipId), request.role().name());
    }

    /**
     * 스터디장을 위임한다(API 명세서 §5.3.3). {@code LEADER}만 호출할 수 있다.
     * 강등 행이 0이면 동시 위임 경쟁에서 패배한 것으로 판단해 AUTH_ACCESS_DENIED를 던진다.
     *
     * @throws StudyException STUDY_NOT_FOUND, STUDY_ALREADY_CLOSED, AUTH_ACCESS_DENIED, STUDY_MEMBERSHIP_NOT_FOUND
     */
    @Transactional
    public LeaderDelegationResponse delegateLeader(Long studyId, Long requesterId, LeaderDelegationRequest request) {
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));
        if (study.getStatus() == StudyStatus.CLOSED) {
            throw new StudyException(StudyErrorCode.STUDY_ALREADY_CLOSED);
        }

        StudyMembership leader
            = membershipRepository
                .findByStudyIdAndRoleAndStatus(studyId, MembershipRole.LEADER, MembershipStatus.APPROVED)
                .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));
        if (!leader.getMemberId().equals(requesterId)) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        if (leader.getId().equals(request.targetMembershipId())) {
            throw new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND);
        }

        StudyMembership target
            = membershipRepository
                .findByIdAndStudyIdAndStatus(request.targetMembershipId(), studyId, MembershipStatus.APPROVED)
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND));

        MembershipRole prevTargetRole = target.getRole();

        int demoted = membershipRepository.demoteLeader(leader.getId());
        if (demoted == 0) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        membershipRepository.promoteToLeader(target.getId());

        if (prevTargetRole == MembershipRole.MEMBER) {
            studyRepository.incrementManagerCountForDelegation(studyId);
        }

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        leader.getId(),
                        MembershipStatus.APPROVED,
                        MembershipStatus.APPROVED,
                        MembershipRole.LEADER,
                        MembershipRole.MANAGER,
                        requesterId,
                        null,
                        now
                    )
            );
        historyRepository
            .save(
                StudyMembershipHistory
                    .record(
                        target.getId(),
                        MembershipStatus.APPROVED,
                        MembershipStatus.APPROVED,
                        prevTargetRole,
                        MembershipRole.LEADER,
                        requesterId,
                        null,
                        now
                    )
            );

        return new LeaderDelegationResponse(String.valueOf(request.targetMembershipId()));
    }
}
