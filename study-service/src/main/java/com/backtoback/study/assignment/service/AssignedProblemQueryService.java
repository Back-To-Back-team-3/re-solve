package com.backtoback.study.assignment.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyStatus;

import lombok.RequiredArgsConstructor;

/**
 * 개인 태그 레벨 잠금의 예외가 되는 배정 문제 여부를 판정한다 (정책 및 상태 §8.3 문제 접근 제한).
 * 레벨 잠금 판정과 예외 적용은 호출하는 judge·프론트가 맡고, 이 서비스는 배정 여부만 반환한다.
 */
@Service
@RequiredArgsConstructor
public class AssignedProblemQueryService {

    // 정책 §8.3은 공개 전(SCHEDULED) 문제집을 빼고 OPEN·CLOSED 문제집의 문제만 배정 문제로 본다.
    private static final List<AssignmentStatus> ASSIGNED_STATUSES
        = List.of(AssignmentStatus.OPEN, AssignmentStatus.CLOSED);

    private final AssignmentProblemRepository assignmentProblemRepository;

    /**
     * 배정 문제가 아니거나 없는 문제도 예외 없이 {@code false}를 반환한다 (API 명세서 5.4.5·5.7.4).
     */
    @Transactional(readOnly = true)
    public boolean isAssigned(Long memberId, Long problemId) {
        return assignmentProblemRepository
            .existsAssignedProblem(
                memberId,
                problemId,
                AssignmentProblemStatus.ACTIVE,
                ASSIGNED_STATUSES,
                MembershipStatus.APPROVED,
                StudyStatus.CLOSED
            );
    }
}
