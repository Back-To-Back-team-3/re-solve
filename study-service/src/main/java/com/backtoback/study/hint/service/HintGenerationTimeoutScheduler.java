package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.repository.HintRequestRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 1분 넘게 멈춘 힌트 요청을 회수하는 스케줄러(스케줄러 명세서 §6.1).
 * ShedLock으로 다중 인스턴스 중복 실행을 막고, 건당 독립 트랜잭션으로 1건 실패가 회차 전체를 멈추지 않게 한다.
 * 이미 멈춘 요청을 정리해야 하므로 힌트 기능 토글과 관계없이 실행한다.
 */
@Component
@RequiredArgsConstructor
public class HintGenerationTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(HintGenerationTimeoutScheduler.class);
    private static final int BATCH_SIZE = 100;
    private static final long STALE_MINUTES = 1;
    private static final List<HintRequestStatus> IN_PROGRESS_STATUSES
        = List.of(HintRequestStatus.REQUESTED, HintRequestStatus.GENERATING);
    private static final String CORRELATION_PREFIX = "sched-HintGenerationTimeoutScheduler-";

    private final HintRecoveryService hintRecoveryService;
    private final HintBlockCheckService hintBlockCheckService;
    private final HintRequestRepository hintRequestRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    private Counter fallbackCounter;
    private Counter blockedCounter;
    private Counter failedCounter;

    @PostConstruct
    void initMetrics() {
        this.fallbackCounter
            = Counter.builder("scheduler.items").tag("result", "hint_recovered_fallback").register(meterRegistry);
        this.blockedCounter
            = Counter.builder("scheduler.items").tag("result", "hint_recovered_blocked").register(meterRegistry);
        this.failedCounter = Counter.builder("scheduler.items").tag("result", "failed").register(meterRegistry);
    }

    /**
     * 1분마다 기준 시각이 1분 이상 지난 REQUESTED·GENERATING 요청을 최대 100건 회수한다.
     * 각 건은 독립 트랜잭션으로 처리하므로 예외가 발생해도 나머지 건은 계속 처리한다.
     */
    @Scheduled(
        fixedDelay = 60_000,
        initialDelay = 60_000
    )
    @SchedulerLock(
        name = "HintGenerationTimeoutScheduler",
        lockAtMostFor = "PT50S"
    )
    public void recoverStalledRequests() {
        LocalDateTime cutoff = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS).minusMinutes(STALE_MINUTES);
        List<HintRequest> targets
            = hintRequestRepository.findRecoveryTargets(IN_PROGRESS_STATUSES, cutoff, PageRequest.of(0, BATCH_SIZE));

        for (HintRequest target : targets) {
            String correlationId = CORRELATION_PREFIX + UUID.randomUUID();
            try {
                // 차단 판정은 트랜잭션 밖에서 먼저 한다. 조회 실패가 회수 트랜잭션을 롤백 전용으로 만들지 않게 한다.
                Optional<String> blockedReason
                    = hintBlockCheckService.findBlockedReason(target.getMemberId(), target.getProblemId());
                HintRecoveryService.Result result = hintRecoveryService.recover(target, blockedReason, correlationId);
                countResult(result);
            } catch (Exception e) {
                log.error("힌트 요청 회수 실패. hintRequestId={}, correlationId={}", target.getId(), correlationId, e);
                failedCounter.increment();
            }
        }
    }

    private void countResult(HintRecoveryService.Result result) {
        if (result == HintRecoveryService.Result.FALLBACK) {
            fallbackCounter.increment();
        } else if (result == HintRecoveryService.Result.BLOCKED) {
            blockedCounter.increment();
        }
    }
}
