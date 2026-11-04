package com.backtoback.study.assignment.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import com.backtoback.study.assignment.dto.MyStatusResponse;
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
@Transactional(readOnly = true)
public class AssignmentQueryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    private final StudyMembershipRepository membershipRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentMemberStatusRepository memberStatusRepository;
    private final AssignmentCompletionRepository completionRepository;
    private final MemberReplicaRepository memberReplicaRepository;

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
            = problems
                .stream()
                .map(
                    p -> new ProblemSnapshotResponse(
                        String.valueOf(p.getProblemId()),
                        String.valueOf(p.getAssignedRevisionId()),
                        null,
                        p.getProblemTitle(),
                        p.getProblemDifficulty(),
                        p.getProblemTagsJson(),
                        p.getStatus(),
                        myCompletions.get(p.getProblemId())
                    )
                )
                .toList();

        List<MemberProgressResponse> memberProgress
            = memberStatuses
                .stream()
                .map(
                    m -> new MemberProgressResponse(
                        String.valueOf(m.getMemberId()),
                        nicknameMap.get(m.getMemberId()),
                        m.getStatus(),
                        m.getCompletedCount(),
                        m.getOnTimeCount(),
                        activeCount
                    )
                )
                .toList();

        return new AssignmentDetailResponse(
            String.valueOf(assignment.getId()),
            assignment.getTitle(),
            assignment.getGoal(),
            assignment.getTagId() != null ? String.valueOf(assignment.getTagId()) : null,
            assignment.getStatus(),
            assignment.isPreSolvedAllowed(),
            toOffsetString(assignment.getStartsAt()),
            toOffsetString(assignment.getDeadlineAt()),
            problemSnapshots,
            memberProgress,
            computeStats(memberStatuses, activeCount)
        );
    }

    private StudyStatsResponse computeStats(List<AssignmentMemberStatus> memberStatuses, int activeCount) {
        if (activeCount == 0 || memberStatuses.isEmpty()) {
            return new StudyStatsResponse(null, null);
        }
        double avgCompletion
            = memberStatuses
                .stream()
                .mapToDouble(m -> (double)m.getCompletedCount() / activeCount)
                .average()
                .orElse(0.0);
        double avgOnTime
            = memberStatuses.stream().mapToDouble(m -> (double)m.getOnTimeCount() / activeCount).average().orElse(0.0);
        return new StudyStatsResponse(Math.round(avgCompletion * 100.0) / 100.0, Math.round(avgOnTime * 100.0) / 100.0);
    }

    private AssignmentSummaryResponse toSummary(Assignment a, int activeCount, AssignmentMemberStatus ams) {
        MyStatusResponse myStatus
            = ams == null ? null : new MyStatusResponse(ams.getStatus(), ams.getCompletedCount(), ams.getOnTimeCount());
        return new AssignmentSummaryResponse(
            String.valueOf(a.getId()),
            a.getTitle(),
            a.getStatus(),
            toOffsetString(a.getStartsAt()),
            toOffsetString(a.getDeadlineAt()),
            activeCount,
            myStatus
        );
    }

    private static String toOffsetString(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.of("+09:00")).format(FORMATTER);
    }
}
