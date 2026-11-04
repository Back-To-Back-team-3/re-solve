package com.backtoback.study.assignment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentCompletion;
import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.domain.CompletionType;
import com.backtoback.study.assignment.domain.MemberProgressStatus;
import com.backtoback.study.assignment.repository.AssignmentCompletionRepository;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.replica.domain.MemberAcReplica;
import com.backtoback.study.replica.repository.MemberAcReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 가입·승인 시 SCHEDULED·OPEN 문제집에 멤버를 편입한다(API 명세서 §5.2.2, §5.2.5).
 * 호출자의 트랜잭션에 참여하며 독립적으로 커밋하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AssignmentEnrollmentService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentMemberStatusRepository memberStatusRepository;
    private final AssignmentCompletionRepository completionRepository;
    private final MemberAcReplicaRepository acReplicaRepository;
    private final Clock clock;

    /**
     * 스터디의 SCHEDULED·OPEN 문제집에 멤버를 편입한다.
     * INSERT IGNORE로 멱등하며, 이미 편입된 경우 조용히 종료한다(ERD §6.5).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void enroll(Long studyId, Long memberId, Long membershipId) {
        List<Assignment> assignments
            = assignmentRepository
                .findByStudyIdAndStatusIn(studyId, List.of(AssignmentStatus.SCHEDULED, AssignmentStatus.OPEN));

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        for (Assignment assignment : assignments) {
            if (assignment.getStatus() == AssignmentStatus.SCHEDULED) {
                enrollScheduled(assignment.getId(), memberId, membershipId);
            } else {
                enrollOpen(assignment, memberId, membershipId, now);
            }
        }
    }

    /**
     * 스케줄러 OPEN 전이에서 멤버 1명을 편입한다(스케줄러 §5.2 동작 2·3·4단계).
     * AC 복제본을 잠금 읽기로 조회해 REPEATABLE READ 스냅샷 경합을 방지한다.
     */
    public void enrollOpenForScheduler(Assignment assignment, Long memberId, Long membershipId, LocalDateTime now) {
        enrollOpenInternal(assignment, memberId, membershipId, now, true);
    }

    private void enrollScheduled(Long assignmentId, Long memberId, Long membershipId) {
        memberStatusRepository
            .insertIgnore(assignmentId, memberId, membershipId, MemberProgressStatus.NOT_STARTED.name(), 0, 0, null);
    }

    private void enrollOpen(Assignment assignment, Long memberId, Long membershipId, LocalDateTime now) {
        enrollOpenInternal(assignment, memberId, membershipId, now, false);
    }

    private void enrollOpenInternal(
        Assignment assignment,
        Long memberId,
        Long membershipId,
        LocalDateTime now,
        boolean withLock
    ) {
        List<AssignmentProblem> activeProblems
            = assignmentProblemRepository
                .findByAssignmentIdAndStatus(assignment.getId(), AssignmentProblemStatus.ACTIVE);

        if (activeProblems.isEmpty()) {
            memberStatusRepository
                .insertIgnore(
                    assignment.getId(),
                    memberId,
                    membershipId,
                    MemberProgressStatus.NOT_STARTED.name(),
                    0,
                    0,
                    null
                );
            return;
        }

        List<Long> activeProblemIds = activeProblems.stream().map(AssignmentProblem::getProblemId).toList();

        List<MemberAcReplica> acReplicas
            = withLock ? acReplicaRepository.findAllAcceptedByMemberIdAndProblemIdsWithLock(memberId, activeProblemIds)
                : acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(memberId, activeProblemIds);

        // preSolvedAllowed=false 시 starts_at 이전 제출은 근거에서 제외한다(ERD §4.5.7 completed_at 불변식)
        // JPQL 쿼리가 (problemId, submittedAt) ASC로 정렬하므로 필터 후 문제별 첫 번째가 earliest valid AC
        Map<Long, MemberAcReplica> earliestAcByProblem
            = acReplicas
                .stream()
                .filter(
                    ac -> assignment.isPreSolvedAllowed() || !ac.getSubmittedAt().isBefore(assignment.getStartsAt())
                )
                .collect(Collectors.toMap(MemberAcReplica::getProblemId, r -> r, (first, second) -> first));

        List<AssignmentCompletion> completions = buildCompletions(assignment, memberId, earliestAcByProblem, now);

        int completedCount = completions.size();
        int onTimeCount = (int)completions.stream().filter(c -> c.getCompletionType() != CompletionType.LATE).count();
        MemberProgressStatus progressStatus = resolveProgressStatus(completedCount, activeProblems.size());
        LocalDateTime completedAt = (progressStatus == MemberProgressStatus.COMPLETED) ? now : null;

        int affected
            = memberStatusRepository
                .insertIgnore(
                    assignment.getId(),
                    memberId,
                    membershipId,
                    progressStatus.name(),
                    completedCount,
                    onTimeCount,
                    completedAt
                );

        if (affected == 0) {
            // SCHEDULED 편입 후 OPEN 전이 시 기존 행 갱신(스케줄러 §5.2 OPEN upsert)
            memberStatusRepository
                .updateStatusAndCounts(
                    assignment.getId(),
                    memberId,
                    progressStatus.name(),
                    completedCount,
                    onTimeCount,
                    completedAt
                );
        }

        for (AssignmentCompletion completion : completions) {
            completionRepository
                .insertIgnore(
                    completion.getAssignmentId(),
                    completion.getMemberId(),
                    completion.getProblemId(),
                    completion.getCompletionType().name(),
                    completion.getBasisSubmissionId(),
                    completion.getBasisSubmittedAt(),
                    completion.getBasisJudgeAttempt(),
                    completion.getCompletedAt()
                );
        }
    }

    private List<AssignmentCompletion> buildCompletions(
        Assignment assignment,
        Long memberId,
        Map<Long, MemberAcReplica> earliestAcByProblem,
        LocalDateTime now
    ) {
        List<AssignmentCompletion> completions = new ArrayList<>();

        for (Map.Entry<Long, MemberAcReplica> entry : earliestAcByProblem.entrySet()) {
            MemberAcReplica ac = entry.getValue();
            CompletionType type = resolveCompletionType(ac.getSubmittedAt(), assignment);
            if (type == null) {
                continue;
            }
            completions
                .add(
                    AssignmentCompletion
                        .complete(
                            assignment.getId(),
                            memberId,
                            entry.getKey(),
                            type,
                            ac.getSubmissionId(),
                            ac.getSubmittedAt(),
                            ac.getJudgeAttempt(),
                            now
                        )
                );
        }

        return completions;
    }

    private CompletionType resolveCompletionType(LocalDateTime submittedAt, Assignment assignment) {
        if (submittedAt.isBefore(assignment.getStartsAt())) {
            // 사전 풀이 불허 시 사전 제출은 근거로 인정하지 않는다(ERD §4.5.8 completion_type 불변식)
            return assignment.isPreSolvedAllowed() ? CompletionType.PRE_SOLVED : null;
        }
        if (!submittedAt.isAfter(assignment.getDeadlineAt())) {
            return CompletionType.ON_TIME;
        }
        return CompletionType.LATE;
    }

    private MemberProgressStatus resolveProgressStatus(int completedCount, int totalActiveCount) {
        if (completedCount == 0) {
            return MemberProgressStatus.NOT_STARTED;
        }
        if (completedCount >= totalActiveCount) {
            return MemberProgressStatus.COMPLETED;
        }
        return MemberProgressStatus.IN_PROGRESS;
    }
}
