package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.event.dto.ContestTerminatedPayload;
import com.backtoback.study.hint.event.dto.DiagnosisClosedPayload;
import com.backtoback.study.hint.event.dto.DiagnosisStartedPayload;
import com.backtoback.study.hint.event.dto.ExamTerminatedPayload;
import com.backtoback.study.hint.event.dto.ExamUpdatedPayload;
import com.backtoback.study.hint.event.dto.ParticipantCanceledPayload;
import com.backtoback.study.hint.event.dto.ParticipantRegisteredPayload;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시험·대회 이벤트를 소비하여 hint_block_replicas를 갱신한다
 * (event-contract.md §C3–§C10·§C13·§C14).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HintBlockReplicaConsumerService {

    private final Clock clock;
    private final JdbcProcessedEventStore processedEventStore;
    private final HintBlockReplicaRepository hintBlockReplicaRepository;

    @Transactional
    public void consumeParticipantRegistered(ParticipantRegisteredPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("{} 중복 수신 무시 eventId={}", payload.eventType(), payload.eventId());
            return;
        }

        HintBlockContextType contextType = HintBlockContextType.valueOf(payload.context());
        Long contextId = Long.parseLong(payload.contextId());
        Long memberId = Long.parseLong(payload.memberId());
        int contextRevision = Integer.parseInt(payload.contextRevision());
        LocalDateTime startsAt = toLocalDateTime(payload.startsAt());
        LocalDateTime endsAt = toLocalDateTime(payload.endsAt());

        if (payload.problemIds() == null) {
            throw new IllegalArgumentException(payload.eventType() + " problemIds 누락 eventId=" + payload.eventId());
        }
        for (String problemIdStr : payload.problemIds()) {
            hintBlockReplicaRepository
                .insertIgnore(
                    memberId,
                    contextType.name(),
                    contextId,
                    Long.parseLong(problemIdStr),
                    startsAt,
                    endsAt,
                    contextRevision
                );
        }
    }

    @Transactional
    public void consumeParticipantCanceled(ParticipantCanceledPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("{} 중복 수신 무시 eventId={}", payload.eventType(), payload.eventId());
            return;
        }

        HintBlockContextType contextType = HintBlockContextType.valueOf(payload.context());
        Long contextId = Long.parseLong(payload.contextId());
        Long memberId = Long.parseLong(payload.memberId());
        hintBlockReplicaRepository.deleteByMemberIdAndContextTypeAndContextId(memberId, contextType, contextId);
    }

    @Transactional
    public void consumeExamUpdated(ExamUpdatedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("ExamUpdated 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

        Long examId = Long.parseLong(payload.examId());
        int incomingRevision = Integer.parseInt(payload.examRevision());

        int maxRevision
            = hintBlockReplicaRepository
                .findMaxContextRevisionByContextTypeAndContextId(HintBlockContextType.EXAM, examId)
                .orElse(0);

        if (incomingRevision <= maxRevision) {
            log.info("ExamUpdated 역전 무시 examId={} incoming={} stored={}", examId, incomingRevision, maxRevision);
            return;
        }

        if (payload.targetMemberIds() == null || payload.problems() == null) {
            throw new IllegalArgumentException(
                "ExamUpdated targetMemberIds 또는 problems 누락 eventId=" + payload.eventId()
            );
        }
        hintBlockReplicaRepository.deleteByContextTypeAndContextId(HintBlockContextType.EXAM, examId);

        LocalDateTime startsAt = toLocalDateTime(payload.startsAt());
        LocalDateTime endsAt = toLocalDateTime(payload.endsAt());
        for (String memberIdStr : payload.targetMemberIds()) {
            Long memberId = Long.parseLong(memberIdStr);
            for (ExamUpdatedPayload.ExamProblem problem : payload.problems()) {
                hintBlockReplicaRepository
                    .insertIgnore(
                        memberId,
                        HintBlockContextType.EXAM.name(),
                        examId,
                        Long.parseLong(problem.problemId()),
                        startsAt,
                        endsAt,
                        incomingRevision
                    );
            }
        }
    }

    @Transactional
    public void consumeExamTerminated(ExamTerminatedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("{} 중복 수신 무시 eventId={}", payload.eventType(), payload.eventId());
            return;
        }

        hintBlockReplicaRepository
            .deleteByContextTypeAndContextId(HintBlockContextType.EXAM, Long.parseLong(payload.examId()));
    }

    @Transactional
    public void consumeContestTerminated(ContestTerminatedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("{} 중복 수신 무시 eventId={}", payload.eventType(), payload.eventId());
            return;
        }

        hintBlockReplicaRepository
            .deleteByContextTypeAndContextId(HintBlockContextType.CONTEST, Long.parseLong(payload.contestId()));
    }

    @Transactional
    public void consumeDiagnosisStarted(DiagnosisStartedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("DiagnosisStarted 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

        Long diagnosisAttemptId = Long.parseLong(payload.diagnosisAttemptId());
        Long memberId = Long.parseLong(payload.memberId());
        LocalDateTime startsAt = toLocalDateTime(payload.startsAt());
        LocalDateTime endsAt = toLocalDateTime(payload.endsAt());

        if (payload.problemIds() == null) {
            throw new IllegalArgumentException("DiagnosisStarted problemIds 누락 eventId=" + payload.eventId());
        }
        for (String problemIdStr : payload.problemIds()) {
            hintBlockReplicaRepository
                .insertIgnore(
                    memberId,
                    HintBlockContextType.DIAGNOSIS.name(),
                    diagnosisAttemptId,
                    Long.parseLong(problemIdStr),
                    startsAt,
                    endsAt,
                    1
                );
        }
    }

    @Transactional
    public void consumeDiagnosisClosed(DiagnosisClosedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("DiagnosisClosed 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

        hintBlockReplicaRepository
            .deleteByContextTypeAndContextId(
                HintBlockContextType.DIAGNOSIS,
                Long.parseLong(payload.diagnosisAttemptId())
            );
    }

    private static LocalDateTime toLocalDateTime(String dateTimeStr) {
        return OffsetDateTime
            .parse(dateTimeStr)
            .toInstant()
            .atZone(ClockConfiguration.SERVICE_ZONE)
            .toLocalDateTime()
            .truncatedTo(ChronoUnit.MICROS);
    }
}
