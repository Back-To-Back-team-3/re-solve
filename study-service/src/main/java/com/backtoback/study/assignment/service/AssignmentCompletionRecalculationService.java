package com.backtoback.study.assignment.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentCompletion;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.CompletionStatus;
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
 * 재채점 결과를 반영해 완료 기록과 멤버 진행 상태를 재계산한다(정책 및 상태 §재채점 반영).
 * 근거 AC 선택 → 완료 기록 갱신 → 멤버 상태 동기화를 단일 트랜잭션으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class AssignmentCompletionRecalculationService {

    private final AssignmentRepository assignmentRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentCompletionRepository completionRepository;
    private final AssignmentMemberStatusRepository memberStatusRepository;
    private final MemberAcReplicaRepository memberAcReplicaRepository;
    private final CompletionTypeCalculator completionTypeCalculator;
    private final Clock clock;

    /**
     * 문제 1개의 재채점 결과를 반영해 완료 기록과 멤버 진행 상태를 갱신한다.
     */
    @Transactional
    public void recalculate(Long assignmentId, Long memberId, Long problemId) {
        Assignment assignment
            = assignmentRepository
                .findById(assignmentId)
                .orElseThrow(() -> new IllegalStateException("Assignment not found: " + assignmentId));

        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);

        Optional<MemberAcReplica> basisAc = findBasisAc(assignment, memberId, problemId);
        Optional<AssignmentCompletion> existing
            = completionRepository.findByAssignmentIdAndMemberIdAndProblemId(assignmentId, memberId, problemId);

        updateCompletion(assignment, memberId, problemId, basisAc, existing, now);
        syncMemberStatus(assignment, memberId, now);
    }

    private Optional<MemberAcReplica> findBasisAc(Assignment assignment, Long memberId, Long problemId) {
        if (assignment.isPreSolvedAllowed()) {
            return memberAcReplicaRepository
                .findEarliestBasisAc(memberId, problemId, PageRequest.of(0, 1))
                .stream()
                .findFirst();
        }
        return memberAcReplicaRepository
            .findEarliestBasisAcAfter(memberId, problemId, assignment.getStartsAt(), PageRequest.of(0, 1))
            .stream()
            .findFirst();
    }

    private void updateCompletion(
        Assignment assignment,
        Long memberId,
        Long problemId,
        Optional<MemberAcReplica> basisAc,
        Optional<AssignmentCompletion> existing,
        LocalDateTime now
    ) {
        if (basisAc.isEmpty()) {
            existing.ifPresent(c -> {
                if (c.getStatus() == CompletionStatus.COMPLETED) {
                    c.revoke();
                }
            });
            return;
        }

        MemberAcReplica ac = basisAc.get();
        CompletionType newType
            = completionTypeCalculator
                .calculate(ac.getSubmittedAt(), assignment.getStartsAt(), assignment.getDeadlineAt());

        if (existing.isEmpty()) {
            completionRepository
                .save(
                    AssignmentCompletion
                        .complete(
                            assignment.getId(),
                            memberId,
                            problemId,
                            newType,
                            ac.getSubmissionId(),
                            ac.getSubmittedAt(),
                            ac.getJudgeAttempt(),
                            now
                        )
                );
            return;
        }

        AssignmentCompletion completion = existing.get();
        if (completion.getStatus() == CompletionStatus.REVOKED) {
            completion.reactivate(newType, ac.getSubmissionId(), ac.getSubmittedAt(), ac.getJudgeAttempt(), now);
            return;
        }

        // COMPLETED: 근거가 동일하면 no-op
        if (ac.getSubmissionId().equals(completion.getBasisSubmissionId())
            && ac.getJudgeAttempt() == completion.getBasisJudgeAttempt()) {
            return;
        }
        completion.updateBasis(newType, ac.getSubmissionId(), ac.getSubmittedAt(), ac.getJudgeAttempt());
    }

    private void syncMemberStatus(Assignment assignment, Long memberId, LocalDateTime now) {
        int totalCount
            = assignmentProblemRepository
                .findByAssignmentIdAndStatus(assignment.getId(), AssignmentProblemStatus.ACTIVE)
                .size();

        List<AssignmentCompletion> completions
            = completionRepository.findByAssignmentIdAndMemberId(assignment.getId(), memberId);

        int completedCount = (int)completions.stream().filter(c -> c.getStatus() == CompletionStatus.COMPLETED).count();
        int onTimeCount
            = (int)completions
                .stream()
                .filter(
                    c -> c.getStatus() == CompletionStatus.COMPLETED && c.getCompletionType() != CompletionType.LATE
                )
                .count();

        MemberProgressStatus newStatus
            = resolveProgressStatus(completedCount, totalCount, assignment.getDeadlineAt(), now);
        LocalDateTime completedAt = (newStatus == MemberProgressStatus.COMPLETED) ? now : null;

        memberStatusRepository
            .updateStatusAndCounts(
                assignment.getId(),
                memberId,
                newStatus.name(),
                completedCount,
                onTimeCount,
                completedAt
            );
    }

    private MemberProgressStatus resolveProgressStatus(
        int completedCount,
        int totalCount,
        LocalDateTime deadlineAt,
        LocalDateTime now
    ) {
        if (totalCount > 0 && completedCount >= totalCount) {
            return MemberProgressStatus.COMPLETED;
        }
        if (deadlineAt != null && now.isAfter(deadlineAt)) {
            return MemberProgressStatus.INCOMPLETE;
        }
        if (completedCount == 0) {
            return MemberProgressStatus.NOT_STARTED;
        }
        return MemberProgressStatus.IN_PROGRESS;
    }
}
