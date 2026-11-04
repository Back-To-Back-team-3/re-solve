package com.backtoback.study.assignment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentCompletion;
import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.domain.CompletionStatus;
import com.backtoback.study.assignment.domain.CompletionType;
import com.backtoback.study.assignment.dto.AssignmentDetailResponse;
import com.backtoback.study.assignment.dto.AssignmentSummaryResponse;
import com.backtoback.study.assignment.dto.MemberProgressResponse;
import com.backtoback.study.assignment.dto.ProblemSnapshotResponse;
import com.backtoback.study.assignment.dto.StudyStatsResponse;
import com.backtoback.study.assignment.repository.AssignmentCompletionRepository;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 문제집 조회 서비스 (API 명세서 §5.4.2·§5.4.3).
 */
@Service
@RequiredArgsConstructor
public class AssignmentQueryService {

    private final StudyMembershipRepository membershipRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentMemberStatusRepository memberStatusRepository;
    private final AssignmentCompletionRepository completionRepository;
    private final MemberReplicaRepository memberReplicaRepository;

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    public AssignmentDetailResponse getAssignmentDetail(Long studyId, Long memberId, Long assignmentId) {
        membershipRepository
            .findByStudyIdAndMemberIdAndStatusIn(studyId, memberId, List.of(MembershipStatus.APPROVED))
            .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));

        Assignment assignment
            = assignmentRepository
                .findByIdAndStudyId(assignmentId, studyId)
                .orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_ASSIGNMENT_NOT_FOUND));

        List<AssignmentProblem> problems
            = assignmentProblemRepository.findByAssignmentIdOrderByDisplayOrderAsc(assignmentId);

        int activeCount = (int)problems.stream().filter(p -> p.getStatus() == AssignmentProblemStatus.ACTIVE).count();

        Map<Long, CompletionType> myCompletions
            = completionRepository
                .findByAssignmentIdAndMemberId(assignmentId, memberId)
                .stream()
                .filter(c -> c.getStatus() == CompletionStatus.COMPLETED)
                .collect(Collectors.toMap(AssignmentCompletion::getProblemId, AssignmentCompletion::getCompletionType));

        List<Long> approvedMemberIds
            = membershipRepository
                .findMemberIdsByStudyIdAndRolesAndStatus(
                    studyId,
                    List.of(MembershipRole.LEADER, MembershipRole.MANAGER, MembershipRole.MEMBER),
                    MembershipStatus.APPROVED
                );

        List<AssignmentMemberStatus> memberStatuses
            = memberStatusRepository.findByAssignmentIdAndMemberIdIn(assignmentId, approvedMemberIds);

        Map<Long, String> nicknameMap
            = memberReplicaRepository
                .findAllByMemberIdIn(approvedMemberIds)
                .stream()
                .collect(Collectors.toMap(r -> r.getMemberId(), r -> r.getNickname()));

        List<ProblemSnapshotResponse> problemSnapshots
            = problems.stream().map(p -> ProblemSnapshotResponse.from(p, myCompletions.get(p.getProblemId()))).toList();

        Set<Long> enrolledMemberIds
            = memberStatuses.stream().map(AssignmentMemberStatus::getMemberId).collect(Collectors.toSet());

        List<MemberProgressResponse> memberProgressList = new ArrayList<>();
        memberStatuses
            .stream()
            .map(m -> MemberProgressResponse.from(m, nicknameMap.get(m.getMemberId()), activeCount))
            .forEach(memberProgressList::add);
        // SCHEDULED 등 미편입 승인 구성원을 NOT_STARTED(0/0)으로 보완
        approvedMemberIds
            .stream()
            .filter(id -> !enrolledMemberIds.contains(id))
            .map(id -> MemberProgressResponse.notStarted(id, nicknameMap.get(id), activeCount))
            .forEach(memberProgressList::add);

        return AssignmentDetailResponse
            .from(
                assignment,
                problemSnapshots,
                memberProgressList,
                computeStats(memberStatuses, approvedMemberIds.size(), activeCount)
            );
    }

    private StudyStatsResponse computeStats(
        List<AssignmentMemberStatus> memberStatuses,
        int totalMemberCount,
        int activeCount
    ) {
        if (activeCount == 0 || totalMemberCount == 0) {
            return new StudyStatsResponse(null, null);
        }
        double sumCompletion
            = memberStatuses.stream().mapToDouble(m -> (double)m.getCompletedCount() / activeCount).sum();
        double sumOnTime = memberStatuses.stream().mapToDouble(m -> (double)m.getOnTimeCount() / activeCount).sum();
        return new StudyStatsResponse(
            Math.round(sumCompletion / totalMemberCount * 100.0) / 100.0,
            Math.round(sumOnTime / totalMemberCount * 100.0) / 100.0
        );
    }

    private AssignmentSummaryResponse toSummary(Assignment assignment, int activeCount, AssignmentMemberStatus ams) {
        return AssignmentSummaryResponse.from(assignment, activeCount, ams);
    }
}
