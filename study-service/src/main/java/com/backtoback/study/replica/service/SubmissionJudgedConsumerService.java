package com.backtoback.study.replica.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.service.AssignmentCompletionRecalculationService;
import com.backtoback.study.replica.domain.JudgeVerdict;
import com.backtoback.study.replica.domain.MemberAcReplica;
import com.backtoback.study.replica.event.dto.SubmissionJudgedPayload;
import com.backtoback.study.replica.repository.MemberAcReplicaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SubmissionJudged 이벤트를 소비하여 AC 복제본을 갱신하고 완료 재계산을 위임한다
 * (event-contract.md §J1, policy-and-state.md §재채점 반영).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionJudgedConsumerService {

    private final JdbcProcessedEventStore processedEventStore;
    private final MemberAcReplicaRepository memberAcReplicaRepository;
    private final AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentMemberStatusRepository assignmentMemberStatusRepository;
    private final AssignmentCompletionRecalculationService recalculationService;

    @Transactional
    public void consume(SubmissionJudgedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            log.warn("SubmissionJudged 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

        boolean shouldRecalculate = applyAcReplica(payload);
        if (!shouldRecalculate) {
            return;
        }

        Long memberId = Long.parseLong(payload.userId());
        Long problemId = Long.parseLong(payload.problemId());

        List<Long> assignmentIds
            = assignmentProblemRepository
                .findOpenAssignmentIdsByProblemId(problemId, AssignmentProblemStatus.ACTIVE, AssignmentStatus.OPEN);
        if (assignmentIds.isEmpty()) {
            return;
        }

        assignmentMemberStatusRepository
            .findByAssignmentIdInAndMemberId(assignmentIds, memberId)
            .forEach(ams -> recalculationService.recalculate(ams.getAssignmentId(), memberId, problemId));
    }

    @Transactional
    public void consumeUnknown(
        String rawEventId,
        String rawEventType,
        String rawOccurredAt,
        String rawAggregateId,
        String rawCorrelationId
    ) {
        OffsetDateTime odt = OffsetDateTime.parse(rawOccurredAt);
        String corrId = (rawCorrelationId != null && !rawCorrelationId.isBlank()) ? rawCorrelationId : "anonymous";
        EventMetadata metadata
            = new EventMetadata(UUID.fromString(rawEventId), rawEventType, "v1", odt, rawAggregateId, corrId);
        try {
            processedEventStore.insert(metadata, LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            log.warn("미지원 이벤트 중복 수신 무시 eventId={} eventType={}", rawEventId, rawEventType);
        }
    }

    private boolean applyAcReplica(SubmissionJudgedPayload payload) {
        Long submissionId = Long.parseLong(payload.submissionId());
        Optional<MemberAcReplica> existing = memberAcReplicaRepository.findBySubmissionId(submissionId);

        if (existing.isEmpty()) {
            if (!payload.isAccepted()) {
                // 케이스 D: 첫 비AC — 복제본 없음, 재계산 불필요
                log.warn("SubmissionJudged 비AC 최초 수신 무시 submissionId={}", payload.submissionId());
                return false;
            }
            // 케이스 A: 신규 AC
            Long memberId = Long.parseLong(payload.userId());
            Long problemId = Long.parseLong(payload.problemId());
            LocalDateTime submittedAt = OffsetDateTime.parse(payload.submittedAt()).toLocalDateTime();
            JudgeVerdict verdict = JudgeVerdict.valueOf(payload.verdict());
            memberAcReplicaRepository
                .save(
                    MemberAcReplica
                        .sync(
                            submissionId,
                            memberId,
                            problemId,
                            submittedAt,
                            payload.judgeAttempt(),
                            verdict,
                            LocalDateTime.now()
                        )
                );
            return true;
        }

        // 케이스 B·C: 조건부 UPDATE로 동시 소비 역전 방지(event-contract.md §5.1 §3)
        JudgeVerdict verdict = JudgeVerdict.valueOf(payload.verdict());
        boolean accepted = verdict == JudgeVerdict.AC;
        int updated
            = memberAcReplicaRepository
                .updateVerdictIfNewer(submissionId, payload.judgeAttempt(), verdict, accepted, LocalDateTime.now());
        if (updated == 0) {
            // 케이스 B: 역순 재채점 — 조건부 UPDATE가 갱신한 행 없음
            log
                .warn(
                    "SubmissionJudged 역순 재채점 무시 submissionId={} 수신={}",
                    payload.submissionId(),
                    payload.judgeAttempt()
                );
            return false;
        }
        // 케이스 C: 순방향 재채점 — 갱신 완료
        return true;
    }
}
