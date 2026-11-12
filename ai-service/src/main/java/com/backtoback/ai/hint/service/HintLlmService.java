package com.backtoback.ai.hint.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.ai.hint.client.HintGeneratingConflictException;
import com.backtoback.ai.hint.client.StudyInternalClient;
import com.backtoback.ai.hint.dto.HintRequestedPayload;
import com.backtoback.ai.hint.event.HintReadyPublishPayload;
import com.backtoback.ai.hint.publisher.HintReadyEventPublisher;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class HintLlmService {

    private static final String SCHEMA_VERSION = "v1";
    private static final String EVENT_TYPE = "HintReady";
    private static final Duration HINT_CACHE_TTL = Duration.ofDays(30);
    private static final Duration FALLBACK_CACHE_TTL = Duration.ofHours(1);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OCCURRED_AT_FMT
        = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");

    private final StudyInternalClient studyInternalClient;
    private final Optional<ChatClient> chatClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final HintReadyEventPublisher hintReadyEventPublisher;
    private final HintPromptTemplate hintPromptTemplate;
    private final Clock clock;
    private final HintGuardrailService hintGuardrailService;
    private final CircuitBreaker llmCircuitBreaker;

    public void process(HintRequestedPayload event) {
        try {
            studyInternalClient.transitionToGenerating(event.hintRequestId());
        } catch (HintGeneratingConflictException e) {
            log.warn("GENERATING 전이 충돌, HintReady 미발행 hintRequestId={}", event.hintRequestId());
            return;
        }

        String cacheKey = cacheKey(event.problemRevisionId(), event.level());
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException e) {
            log.warn("힌트 캐시 조회 실패, 캐시 미스로 처리 hintRequestId={} error={}", event.hintRequestId(), e.getMessage());
            cached = null;
        }
        if (cached != null) {
            log.info("힌트 캐시 적중 hintRequestId={}", event.hintRequestId());
            publishHintReady(event, "READY", true);
            return;
        }

        String fallbackCacheKey = fallbackCacheKey(event.problemRevisionId(), event.level());
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(fallbackCacheKey))) {
                log.info("폴백 마커 캐시 적중 hintRequestId={}", event.hintRequestId());
                publishHintReady(event, "FALLBACK", true);
                return;
            }
        } catch (RuntimeException e) {
            log.warn("폴백 마커 캐시 조회 실패, 캐시 미스로 처리 hintRequestId={} error={}", event.hintRequestId(), e.getMessage());
        }

        if (chatClient.isEmpty()) {
            log.warn("ChatClient 미초기화, 폴백 발행 hintRequestId={}", event.hintRequestId());
            publishHintReady(event, "FALLBACK", false);
            return;
        }

        String hintText;
        try {
            hintText = llmCircuitBreaker.executeSupplier(() -> callLlmWithTimeout(event));
        } catch (CallNotPermittedException e) {
            log.warn("서킷브레이커 OPEN, 폴백 발행 hintRequestId={}", event.hintRequestId());
            publishHintReady(event, "FALLBACK", false);
            return;
        } catch (Exception e) {
            log.warn("LLM 호출 실패 또는 타임아웃, 폴백 발행 hintRequestId={} error={}", event.hintRequestId(), e.getMessage());
            publishHintReady(event, "FALLBACK", false);
            return;
        }

        GuardrailResult guardrailResult = hintGuardrailService.check(hintText, event);

        if (guardrailResult.passed()) {
            try {
                redisTemplate.opsForValue().set(cacheKey, guardrailResult.text(), HINT_CACHE_TTL);
            } catch (RuntimeException e) {
                log
                    .warn(
                        "힌트 캐시 저장 실패, READY 발행은 계속 진행 hintRequestId={} error={}",
                        event.hintRequestId(),
                        e.getMessage()
                    );
            }
            publishHintReady(event, "READY", false);
        } else {
            try {
                redisTemplate.opsForValue().set(fallbackCacheKey, "1", FALLBACK_CACHE_TTL);
            } catch (RuntimeException e) {
                log
                    .warn(
                        "폴백 마커 캐시 저장 실패, FALLBACK 발행은 계속 진행 hintRequestId={} error={}",
                        event.hintRequestId(),
                        e.getMessage()
                    );
            }
            publishHintReady(event, "FALLBACK", false);
        }
    }

    private String callLlmWithTimeout(HintRequestedPayload event) {
        CompletableFuture<String> future
            = CompletableFuture
                .supplyAsync(
                    () -> chatClient
                        .get()
                        .prompt()
                        .system(hintPromptTemplate.systemPrompt())
                        .user(
                            hintPromptTemplate
                                .userPrompt(
                                    event.level(),
                                    event.problemTitle(),
                                    event.problemBody(),
                                    event.problemTags()
                                )
                        )
                        .call()
                        .content()
                );
        try {
            return future.orTimeout(15, TimeUnit.SECONDS).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 호출 인터럽트", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) {
                future.cancel(true);
                throw new RuntimeException("LLM 타임아웃 초과 (15s)", e.getCause());
            }
            throw new RuntimeException("LLM 호출 실패: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private void publishHintReady(HintRequestedPayload event, String result, boolean isCached) {
        String now = ZonedDateTime.now(clock).withZoneSameInstant(KST).format(OCCURRED_AT_FMT);
        hintReadyEventPublisher
            .publish(
                new HintReadyPublishPayload(
                    UUID.randomUUID().toString(),
                    EVENT_TYPE,
                    SCHEMA_VERSION,
                    now,
                    event.hintRequestId(),
                    event.correlationId(),
                    event.hintRequestId(),
                    event.memberId(),
                    event.problemId(),
                    event.problemRevisionId(),
                    event.level(),
                    result,
                    isCached,
                    now
                )
            );
    }

    private String cacheKey(String problemRevisionId, int level) {
        return "hint:cache:" + problemRevisionId + ":" + level;
    }

    private String fallbackCacheKey(String problemRevisionId, int level) {
        return "hint:fallback:" + problemRevisionId + ":" + level;
    }
}
