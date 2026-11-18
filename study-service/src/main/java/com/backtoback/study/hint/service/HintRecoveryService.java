package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.event.HintReadyPayload;
import com.backtoback.study.hint.repository.HintDailyUsageRepository;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 1분 넘게 멈춘 힌트 요청 1건을 회수한다(scheduler §6.1).
 * 전달 직전과 같은 차단 재확인을 거쳐 BLOCKED 또는 FALLBACK(RECOVERED)으로 종결하고 HintReady를 Outbox에 기록한다.
 * 조건부 UPDATE가 0행이면 그사이 ai 응답·생성 시작으로 상태가 바뀐 것이므로 아무것도 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HintRecoveryService {

    private static final String HINT_REQUEST_AGGREGATE_TYPE = "HINT_REQUEST";
    private static final String HINT_READY_EVENT_TYPE = "HintReady";
    private static final String HINT_READY_VERSION = "v1";

    private final Clock clock;
    private final HintRequestRepository hintRequestRepository;
    private final HintDailyUsageRepository hintDailyUsageRepository;
    private final HintBlockCheckService hintBlockCheckService;
    private final OutboxEventWriter outboxEventWriter;

    /** 회수 1건의 처리 결과 — 스케줄러 지표 집계에 쓴다. */
    public enum Result {
        FALLBACK, BLOCKED, SKIPPED
    }

    /**
     * 조회 시점 상태 그대로인 요청만 종결한다. 건마다 독립 트랜잭션이라 1건 실패가 다른 건에 번지지 않는다.
     *
     * @param target 회수 대상으로 조회한 요청(조회 시점 상태를 조건부 UPDATE 기대값으로 쓴다)
     * @param correlationId HintReady에 실을 추적 ID
     * @return 종결 결과. 이미 다른 경로로 바뀐 요청이면 {@link Result#SKIPPED}
     */
    @Transactional
    public Result recover(HintRequest target, String correlationId) {
        OffsetDateTime completedAt
            = OffsetDateTime.now(clock.withZone(ClockConfiguration.SERVICE_ZONE)).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime completedAtLocal = completedAt.toLocalDateTime();

        Optional<String> blockedReason
            = hintBlockCheckService.findBlockedReason(target.getMemberId(), target.getProblemId());

        if (blockedReason.isPresent()) {
            int updated
                = hintRequestRepository
                    .recoverAsBlocked(target.getId(), target.getStatus(), blockedReason.get(), completedAtLocal);
            if (updated == 0) {
                return Result.SKIPPED;
            }
            refundDailyUsage(target, completedAtLocal);
            appendHintReady(target, Result.BLOCKED, completedAt, correlationId);
            return Result.BLOCKED;
        }

        int updated = hintRequestRepository.recoverAsFallback(target.getId(), target.getStatus(), completedAtLocal);
        if (updated == 0) {
            return Result.SKIPPED;
        }
        appendHintReady(target, Result.FALLBACK, completedAt, correlationId);
        return Result.FALLBACK;
    }

    // 차단 종결은 일일 횟수를 차감하지 않으므로 요청일(서울) 사용량을 1회 되돌린다. 되돌릴 횟수가 없어도 회수는 유지한다.
    private void refundDailyUsage(HintRequest target, LocalDateTime now) {
        int refunded
            = hintDailyUsageRepository
                .decrementIfPositive(target.getMemberId(), target.getCreatedAt().toLocalDate(), now);
        if (refunded == 0) {
            log
                .warn(
                    "차단 회수 일일 횟수 반환 대상 없음 hintRequestId={} memberId={} usageDate={}",
                    target.getId(),
                    target.getMemberId(),
                    target.getCreatedAt().toLocalDate()
                );
        }
    }

    // 회수 종결은 캐시 적중이 아니므로 isCached=false로 발행한다(event-contract S8).
    private void appendHintReady(HintRequest target, Result result, OffsetDateTime completedAt, String correlationId) {
        outboxEventWriter
            .append(
                HINT_REQUEST_AGGREGATE_TYPE,
                HINT_READY_EVENT_TYPE,
                HINT_READY_VERSION,
                String.valueOf(target.getId()),
                correlationId,
                new HintReadyPayload(
                    String.valueOf(target.getId()),
                    String.valueOf(target.getMemberId()),
                    String.valueOf(target.getProblemId()),
                    String.valueOf(target.getProblemRevisionId()),
                    target.getLevel(),
                    result.name(),
                    false,
                    completedAt
                )
            );
    }
}
