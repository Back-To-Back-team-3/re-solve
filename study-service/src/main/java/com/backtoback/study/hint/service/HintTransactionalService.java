package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.hint.exception.HintLevelSkippedException;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 접수 트랜잭션 (api-spec §6.1.1 step③④⑤ + Redis 캐시 조회 + INSERT).
 * Outbox 이벤트 발행은 HintRequestedPayload·HintReadyPayload 추가 커밋에서 연동한다.
 */
@Service
@RequiredArgsConstructor
public class HintTransactionalService {

    static final String HINT_CACHE_KEY_PREFIX = "hint:cache:";
    static final Duration HINT_CACHE_TTL = Duration.ofDays(30);

    private static final List<HintRequestStatus> TERMINAL_STATUSES
        = List.of(HintRequestStatus.READY, HintRequestStatus.FALLBACK);

    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final HintRequestRepository hintRequestRepository;
    private final HintDailyLimitService hintDailyLimitService;

    @Transactional
    public HintAcceptResult accept(Long memberId, Long problemId, HintContextResponse context, int level) {
        // step③: 레벨 순서 확인
        checkLevelOrder(memberId, problemId, level);

        // step④: 멱등성 확인 — 동일 (member, revision, level) 행이 이미 존재하면 그대로 반환
        Optional<HintRequest> existing
            = hintRequestRepository
                .findByMemberIdAndProblemRevisionIdAndLevel(memberId, context.problemRevisionId(), level);
        if (existing.isPresent()) {
            return new HintAcceptResult(existing.get().getId(), existing.get().isCached());
        }

        // step⑤: 일일 한도 원자 확보
        hintDailyLimitService.reserve(memberId);

        // Redis 캐시 조회
        String cacheKey = HINT_CACHE_KEY_PREFIX + context.problemRevisionId() + ":" + level;
        boolean isCached = Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey));

        HintRequest saved;
        if (isCached) {
            LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfiguration.SERVICE_ZONE));
            saved
                = hintRequestRepository
                    .save(HintRequest.cacheHit(memberId, problemId, context.problemRevisionId(), level, now));
            // TODO(commit5): HintReady Outbox 발행
        } else {
            saved
                = hintRequestRepository
                    .save(HintRequest.requested(memberId, problemId, context.problemRevisionId(), level));
            // TODO(commit5): HintRequested Outbox 발행
            // 캐시 등록 (ai-service가 생성 완료 후 직접 set하므로 여기서는 TTL 연장 없음)
            redisTemplate.expire(cacheKey, HINT_CACHE_TTL);
        }

        return new HintAcceptResult(saved.getId(), isCached);
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
