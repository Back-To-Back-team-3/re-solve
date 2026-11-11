package com.backtoback.ai.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClientException;

import com.backtoback.ai.hint.client.HintGeneratingConflictException;
import com.backtoback.ai.hint.client.StudyInternalClient;
import com.backtoback.ai.hint.dto.HintRequestedPayload;
import com.backtoback.ai.hint.event.HintReadyPublishPayload;
import com.backtoback.ai.hint.publisher.HintReadyEventPublisher;

@ExtendWith(MockitoExtension.class)
class HintLlmServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    StudyInternalClient studyInternalClient;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    ChatClient chatClient;
    @Mock
    RedisTemplate<String, String> redisTemplate;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    HintReadyEventPublisher hintReadyEventPublisher;
    @Mock
    HintPromptTemplate hintPromptTemplate;
    @Mock
    HintFallbackTextProvider hintFallbackTextProvider;

    HintLlmService sut;

    @BeforeEach
    void setUp() {
        sut
            = new HintLlmService(
                studyInternalClient,
                Optional.of(chatClient),
                redisTemplate,
                hintReadyEventPublisher,
                hintPromptTemplate,
                hintFallbackTextProvider,
                FIXED_CLOCK
            );
    }

    @Test
    @DisplayName("Redis 캐시 적중 시 LLM 미호출, isCached=true로 HintReady 발행")
    void process_cacheHit_publishesHintReadyWithIsCachedTrue() {
        HintRequestedPayload event = makeEvent();
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("캐시된 힌트");

        sut.process(event);

        ArgumentCaptor<HintReadyPublishPayload> captor = ArgumentCaptor.forClass(HintReadyPublishPayload.class);
        then(hintReadyEventPublisher).should().publish(captor.capture());
        assertThat(captor.getValue().isCached()).isTrue();
        assertThat(captor.getValue().result()).isEqualTo("캐시된 힌트");
        then(valueOps).should().get(anyString());
        then(valueOps).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("캐시 미적중 후 LLM 정상 완료 시 isCached=false로 HintReady 발행 및 Redis 저장")
    void process_cacheMiss_llmSuccess_storesInRedisAndPublishesHintReady() {
        HintRequestedPayload event = makeEvent();
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn(null);
        given(hintPromptTemplate.systemPrompt()).willReturn("system");
        given(hintPromptTemplate.userPrompt(anyInt(), any(), any(), any())).willReturn("user");
        given(chatClient.prompt().system(anyString()).user(anyString()).call().content()).willReturn("LLM 힌트");

        sut.process(event);

        then(valueOps).should().set(anyString(), eq("LLM 힌트"), any(Duration.class));
        ArgumentCaptor<HintReadyPublishPayload> captor = ArgumentCaptor.forClass(HintReadyPublishPayload.class);
        then(hintReadyEventPublisher).should().publish(captor.capture());
        assertThat(captor.getValue().isCached()).isFalse();
        assertThat(captor.getValue().result()).isEqualTo("LLM 힌트");
    }

    @Test
    @DisplayName("캐시 미적중 후 LLM 예외(타임아웃 포함) 시 폴백 텍스트로 HintReady 발행, Redis 미저장")
    void process_cacheMiss_llmException_publishesFallbackHintReady() {
        HintRequestedPayload event = makeEvent();
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn(null);
        given(hintPromptTemplate.systemPrompt()).willReturn("system");
        given(hintPromptTemplate.userPrompt(anyInt(), any(), any(), any())).willReturn("user");
        given(chatClient.prompt().system(anyString()).user(anyString()).call().content())
            .willThrow(new RuntimeException("LLM 오류"));
        given(hintFallbackTextProvider.get(1)).willReturn("폴백 텍스트");

        sut.process(event);

        then(valueOps).should().get(anyString());
        then(valueOps).shouldHaveNoMoreInteractions();
        ArgumentCaptor<HintReadyPublishPayload> captor = ArgumentCaptor.forClass(HintReadyPublishPayload.class);
        then(hintReadyEventPublisher).should().publish(captor.capture());
        assertThat(captor.getValue().isCached()).isFalse();
        assertThat(captor.getValue().result()).isEqualTo("폴백 텍스트");
    }

    @Test
    @DisplayName("GENERATING 전이 409 충돌 시 HintReady 미발행 후 종료")
    void process_generatingConflict_doesNotPublishHintReady() {
        HintRequestedPayload event = makeEvent();
        willThrow(new HintGeneratingConflictException("req-001"))
            .given(studyInternalClient)
            .transitionToGenerating("req-001");

        sut.process(event);

        then(hintReadyEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("GENERATING 전이 5xx 시 RestClientException 전파 (SQS NACK 유도)")
    void process_generatingServerError_propagatesRestClientException() {
        HintRequestedPayload event = makeEvent();
        willThrow(new RestClientException("5xx 오류")).given(studyInternalClient).transitionToGenerating("req-001");

        assertThatThrownBy(() -> sut.process(event)).isInstanceOf(RestClientException.class);
        then(hintReadyEventPublisher).shouldHaveNoInteractions();
    }

    private HintRequestedPayload makeEvent() {
        return new HintRequestedPayload(
            "evt-001",
            "HintRequested",
            "1",
            "2026-11-12T00:00:00Z",
            "req-001",
            "corr-001",
            "req-001",
            "mem-001",
            "prob-001",
            "rev-001",
            1,
            "문제 제목",
            "문제 내용",
            List.of("dp", "greedy")
        );
    }
}
