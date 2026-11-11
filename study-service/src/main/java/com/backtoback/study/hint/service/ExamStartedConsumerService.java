package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.domain.HintBlockReplica;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.event.HintReadyPayload;
import com.backtoback.study.hint.event.dto.ExamStartedPayload;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ExamStarted 이벤트를 소비하여 GENERATING 힌트 요청을 BLOCKED로 전이하고 HintReady Outbox를 발행한다
 * (event-contract.md §C8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamStartedConsumerService {

    private static final String HINT_REQUEST_AGGREGATE_TYPE = "HINT_REQUEST";
    private static final String HINT_READY_EVENT_TYPE = "HintReady";
    private static final String HINT_READY_VERSION = "v1";
    private static final String BLOCKED_REASON = "EXAM";

    private final Clock clock;
    private final JdbcProcessedEventStore processedEventStore;
    private final HintBlockReplicaRepository hintBlockReplicaRepository;
    private final HintRequestRepository hintRequestRepository;
    private final OutboxEventWriter outboxEventWriter;

    @Transactional
    public void consume(ExamStartedPayload payload) {
        try {
            processedEventStore.insert(payload.toMetadata(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            log.warn("ExamStarted 중복 수신 무시 eventId={}", payload.eventId());
            return;
        }

        Long examId = Long.parseLong(payload.examId());
        LocalDateTime completedAt = toLocalDateTime(payload.startsAt());
        OffsetDateTime completedAtOffset = OffsetDateTime.parse(payload.startsAt());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        List<HintBlockReplica> replicas
            = hintBlockReplicaRepository.findByContextTypeAndContextId(HintBlockContextType.EXAM, examId);

        for (HintBlockReplica replica : replicas) {
            Optional<HintRequest> opt
                = hintRequestRepository
                    .findByMemberIdAndProblemIdAndStatus(
                        replica.getMemberId(),
                        replica.getProblemId(),
                        HintRequestStatus.GENERATING
                    );
            if (opt.isEmpty()) {
                continue;
            }
            HintRequest hintRequest = opt.get();

            int updated = hintRequestRepository.blockIfGeneratingById(hintRequest.getId(), BLOCKED_REASON, completedAt);
            if (updated == 0) {
                log.info("BLOCKED 전이 대상 없음 (경쟁 처리 완료) hintRequestId={}", hintRequest.getId());
                continue;
            }

            outboxEventWriter
                .append(
                    HINT_REQUEST_AGGREGATE_TYPE,
                    HINT_READY_EVENT_TYPE,
                    HINT_READY_VERSION,
                    String.valueOf(hintRequest.getId()),
                    correlationId,
                    new HintReadyPayload(
                        String.valueOf(hintRequest.getId()),
                        String.valueOf(hintRequest.getMemberId()),
                        String.valueOf(hintRequest.getProblemId()),
                        String.valueOf(hintRequest.getProblemRevisionId()),
                        hintRequest.getLevel(),
                        "BLOCKED",
                        hintRequest.isCached(),
                        completedAtOffset
                    )
                );
        }
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
