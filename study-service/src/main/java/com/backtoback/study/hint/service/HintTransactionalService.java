package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.hint.event.HintReadyPayload;
import com.backtoback.study.hint.event.HintRequestedPayload;
import com.backtoback.study.hint.exception.HintLevelSkippedException;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 접수 트랜잭션 (api-spec §6.1.1 step③④⑤ + Redis 캐시 조회 + INSERT + Outbox 발행).
 */
@Service
@RequiredArgsConstructor
public class HintTransactionalService {

    static final String HINT_CACHE_KEY_PREFIX = "hint:cache:";

    private static final List<HintRequestStatus> TERMINAL_STATUSES
        = List.of(HintRequestStatus.READY, HintRequestStatus.FALLBACK);

    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final HintRequestRepository hintRequestRepository;
    private final HintDailyLimitService hintDailyLimitService;
    private final OutboxEventWriter outboxEventWriter;

    @Transactional
    public HintAcceptResult accept(Long memberId, Long problemId, HintContextResponse context, int level) {
        // step③: 레벨 순서 확인
        checkLevelOrder(memberId, problemId, level);

        // step④: 멱등성 확인 — 동일 (member, revision, level) 행이 이미 존재하면 그대로 반환
        Optional<HintRequest> existing
            = hintRequestRepository
                .findByMemberIdAndProblemRevisionIdAndLevel(memberId, context.problemRevisionId(), level);
        if (existing.isPresent()) {
            HintRequest ex = existing.get();
            int todayUsedCount = hintDailyLimitService.getTodayUsedCount(memberId);
            return new HintAcceptResult(ex.getId(), ex.getStatus(), ex.getContent(), todayUsedCount);
        }

        // step⑤: 일일 한도 원자 확보
        int usedCount = hintDailyLimitService.reserve(memberId);

        // Redis 캐시 조회 — opsForValue().get()으로 존재 확인과 값 읽기를 원자적으로 처리
        String cacheKey = HINT_CACHE_KEY_PREFIX + context.problemRevisionId() + ":" + level;
        String cachedContent = redisTemplate.opsForValue().get(cacheKey);

        String correlationId
            = Objects.requireNonNullElseGet(MDC.get(CorrelationIdFilter.MDC_KEY), () -> UUID.randomUUID().toString());

        HintRequest saved;
        if (cachedContent != null) {
            OffsetDateTime completedAt = OffsetDateTime.now(clock.withZone(ClockConfiguration.SERVICE_ZONE));
            saved
                = hintRequestRepository
                    .save(
                        HintRequest
                            .cacheHit(
                                memberId,
                                problemId,
                                context.problemRevisionId(),
                                level,
                                completedAt.toLocalDateTime()
                            )
                    );
            outboxEventWriter
                .append(
                    "HINT_REQUEST",
                    "HintReady",
                    "v1",
                    String.valueOf(saved.getId()),
                    correlationId,
                    new HintReadyPayload(
                        String.valueOf(saved.getId()),
                        String.valueOf(memberId),
                        String.valueOf(problemId),
                        String.valueOf(context.problemRevisionId()),
                        level,
                        "READY",
                        true,
                        completedAt
                    )
                );
            return new HintAcceptResult(saved.getId(), HintRequestStatus.READY, cachedContent, usedCount);
        } else {
            saved
                = hintRequestRepository
                    .save(HintRequest.requested(memberId, problemId, context.problemRevisionId(), level));
            outboxEventWriter
                .append(
                    "HINT_REQUEST",
                    "HintRequested",
                    "v1",
                    String.valueOf(saved.getId()),
                    correlationId,
                    new HintRequestedPayload(
                        String.valueOf(saved.getId()),
                        String.valueOf(memberId),
                        String.valueOf(problemId),
                        String.valueOf(context.problemRevisionId()),
                        level,
                        context.problemTitle(),
                        context.problemBody(),
                        context.problemTags() != null ? context.problemTags() : List.of()
                    )
                );
            return new HintAcceptResult(saved.getId(), HintRequestStatus.REQUESTED, null, usedCount);
        }
    }

    private void checkLevelOrder(Long memberId, Long problemId, int level) {
        if (level == 1) {
            return;
        }
        boolean prevReady
            = hintRequestRepository
                .existsByMemberIdAndProblemIdAndLevelAndStatusIn(memberId, problemId, level - 1, TERMINAL_STATUSES);
        if (!prevReady) {
            throw new HintLevelSkippedException();
        }
    }
}
