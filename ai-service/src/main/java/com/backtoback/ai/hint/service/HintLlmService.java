package com.backtoback.ai.hint.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class HintLlmService {

    private static final String SCHEMA_VERSION = "1";
    private static final String EVENT_TYPE = "HintReady";
    private static final Duration HINT_CACHE_TTL = Duration.ofDays(7);

    private final StudyInternalClient studyInternalClient;
    private final Optional<ChatClient> chatClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final HintReadyEventPublisher hintReadyEventPublisher;
    private final HintPromptTemplate hintPromptTemplate;
    private final HintFallbackTextProvider hintFallbackTextProvider;
    private final Clock clock;

    public void process(HintRequestedPayload event) {
        try {
            studyInternalClient.transitionToGenerating(event.hintRequestId());
        } catch (HintGeneratingConflictException e) {
            log.warn("GENERATING 전이 충돌, HintReady 미발행 hintRequestId={}", event.hintRequestId());
            return;
        }

        String cacheKey = cacheKey(event.problemId(), event.problemRevisionId(), event.level());
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.info("힌트 캐시 적중 hintRequestId={}", event.hintRequestId());
            publishHintReady(event, cached, true);
            return;
        }

        if (chatClient.isEmpty()) {
            log.warn("ChatClient 미초기화, 폴백 텍스트 사용 hintRequestId={}", event.hintRequestId());
            publishHintReady(event, hintFallbackTextProvider.get(event.level()), false);
            return;
        }

        String result;
        try {
            result = callLlmWithTimeout(event);
        } catch (Exception e) {
            log.warn("LLM 호출 실패 또는 타임아웃, 폴백 텍스트 사용 hintRequestId={} error={}", event.hintRequestId(), e.getMessage());
            publishHintReady(event, hintFallbackTextProvider.get(event.level()), false);
            return;
        }

        redisTemplate.opsForValue().set(cacheKey, result, HINT_CACHE_TTL);
        publishHintReady(event, result, false);
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
        String now = Instant.now(clock).toString();
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

    private String cacheKey(String problemId, String problemRevisionId, int level) {
        return "hint:" + problemId + ":" + problemRevisionId + ":" + level;
    }
}
