package com.backtoback.study.operation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
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
}
