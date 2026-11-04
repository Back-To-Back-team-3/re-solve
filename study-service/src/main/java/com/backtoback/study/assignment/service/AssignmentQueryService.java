package com.backtoback.study.assignment.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.dto.AssignmentSummaryResponse;
import com.backtoback.study.assignment.dto.MyStatusResponse;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.repository.StudyMembershipRepository;

import lombok.RequiredArgsConstructor;

/**
 * 문제집 조회 서비스 (API 명세서 §5.4.2·§5.4.3).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssignmentQueryService {

    private final StudyMembershipRepository membershipRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentMemberStatusRepository memberStatusRepository;

    public Page<AssignmentSummaryResponse> listAssignments(
        Long studyId,
        Long memberId,
        AssignmentStatus statusFilter,
        Pageable pageable
    ) {
        membershipRepository
            .findByStudyIdAndMemberIdAndStatusIn(studyId, memberId, List.of(MembershipStatus.APPROVED))
            .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));

        Page<Assignment> page
            = statusFilter != null ? assignmentRepository.findByStudyIdAndStatus(studyId, statusFilter, pageable)
                : assignmentRepository.findByStudyId(studyId, pageable);

        List<Long> ids = page.getContent().stream().map(Assignment::getId).toList();
        if (ids.isEmpty()) {
            return page.map(a -> toSummary(a, 0, null));
        }

        Map<Long, Integer> activeCounts
            = assignmentProblemRepository
                .countByAssignmentIdInAndStatus(ids, AssignmentProblemStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(v -> v.getAssignmentId(), v -> v.getCount().intValue()));

        Map<Long, AssignmentMemberStatus> myStatuses
            = memberStatusRepository
                .findByAssignmentIdInAndMemberId(ids, memberId)
                .stream()
                .collect(Collectors.toMap(AssignmentMemberStatus::getAssignmentId, Function.identity()));

        return page.map(a -> toSummary(a, activeCounts.getOrDefault(a.getId(), 0), myStatuses.get(a.getId())));
    }

    private AssignmentSummaryResponse toSummary(Assignment a, int activeCount, AssignmentMemberStatus ams) {
        MyStatusResponse myStatus
            = ams == null ? null : new MyStatusResponse(ams.getStatus(), ams.getCompletedCount(), ams.getOnTimeCount());
        return new AssignmentSummaryResponse(
            a.getId(),
            a.getTitle(),
            a.getStatus(),
            a.getStartsAt(),
            a.getDeadlineAt(),
            activeCount,
            myStatus
        );
    }
}
