package com.backtoback.contest.exam.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/**
 * 새 시험 참가 등록에 필요한 스터디 구성원 자격을 판정한다 (API 명세서 4.2.1).
 */
@Service
@RequiredArgsConstructor
public class ExamEligibilityService {
    private final StudyMembershipReplicaRepository memberships;

    /**
     * 복제본이 없거나 비활성이면 등록을 거절한다. 기존 등록 유지 여부는 호출자가 판단한다.
     */
    @Transactional(readOnly = true)
    public void requireApprovedMembership(long studyId, long memberId) {
        if (!memberships.isActive(studyId, memberId)) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_ELIGIBLE);
        }
    }
}
