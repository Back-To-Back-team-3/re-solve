package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.hint.exception.HintLevelSkippedException;
import com.backtoback.study.hint.repository.HintRequestRepository;

/**
 * 힌트 요청 접수 트랜잭션 서비스 단위 테스트(API 명세서 §6.1.1 step③④⑤).
 *
 * @Transactional 경계는 Spring 컨텍스트 없이 비즈니스 로직만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class HintTransactionalServiceTest {

    private static final long MEMBER_ID = 1L;
    private static final long PROBLEM_ID = 100L;
    private static final long REVISION_ID = 200L;

    @Mock
    private HintRequestRepository hintRequestRepository;
    @Mock
    private HintDailyLimitService hintDailyLimitService;
    @Mock
    private OutboxEventWriter outboxEventWriter;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private HintTransactionalService sut;
    private HintContextResponse context;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
        sut
            = new HintTransactionalService(
                clock,
                redisTemplate,
                hintRequestRepository,
                hintDailyLimitService,
                outboxEventWriter
            );
        context = new HintContextResponse(true, REVISION_ID, "문제 제목", "문제 본문", List.of("dp"));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("6.1.1 레벨2 요청 시 이전 레벨이 완료되지 않으면 HINT_LEVEL_SKIPPED 예외가 발생한다")
    void accept_level2_prevLevelNotReady_throwsLevelSkippedException() {
        given(
            hintRequestRepository
                .existsByMemberIdAndProblemIdAndLevelAndStatusIn(eq(MEMBER_ID), eq(PROBLEM_ID), eq(1), anyList())
        ).willReturn(false);

        assertThatThrownBy(() -> sut.accept(MEMBER_ID, PROBLEM_ID, context, 2))
            .isInstanceOf(HintLevelSkippedException.class);

        verify(hintDailyLimitService, never()).reserve(anyLong());
    }

    @Test
    @DisplayName("6.1.1 동일 (member, revision, level) 행이 이미 존재하면 reserve를 호출하지 않고 기존 결과를 반환한다")
    void accept_idempotent_returnsExistingWithoutReserving() {
        HintRequest existing = HintRequest.requested(MEMBER_ID, PROBLEM_ID, REVISION_ID, 1);
        ReflectionTestUtils.setField(existing, "id", 42L);

        given(hintRequestRepository.findByMemberIdAndProblemRevisionIdAndLevel(MEMBER_ID, REVISION_ID, 1))
            .willReturn(Optional.of(existing));
        given(hintDailyLimitService.getTodayUsedCount(MEMBER_ID)).willReturn(5);

        HintAcceptResult result = sut.accept(MEMBER_ID, PROBLEM_ID, context, 1);

        assertThat(result.hintRequestId()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(HintRequestStatus.REQUESTED);
        assertThat(result.todayUsedCount()).isEqualTo(5);
        verify(hintDailyLimitService, never()).reserve(anyLong());
    }

    @Test
    @DisplayName("6.1.1 캐시 미스이면 REQUESTED 상태로 HintRequest를 저장하고 HintRequested 이벤트를 발행한다")
    void accept_cacheMiss_savesRequestedAndPublishesHintRequestedEvent() {
        HintRequest saved = HintRequest.requested(MEMBER_ID, PROBLEM_ID, REVISION_ID, 1);
        ReflectionTestUtils.setField(saved, "id", 10L);

        given(hintRequestRepository.findByMemberIdAndProblemRevisionIdAndLevel(MEMBER_ID, REVISION_ID, 1))
            .willReturn(Optional.empty());
        given(hintDailyLimitService.reserve(MEMBER_ID)).willReturn(3);
        given(valueOperations.get(anyString())).willReturn(null);
        given(hintRequestRepository.save(any())).willReturn(saved);

        HintAcceptResult result = sut.accept(MEMBER_ID, PROBLEM_ID, context, 1);

        assertThat(result.hintRequestId()).isEqualTo(10L);
        assertThat(result.status()).isEqualTo(HintRequestStatus.REQUESTED);
        assertThat(result.content()).isNull();
        assertThat(result.todayUsedCount()).isEqualTo(3);
        verify(outboxEventWriter)
            .append(eq("HINT_REQUEST"), eq("HintRequested"), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("6.1.1 캐시 히트이면 READY 상태로 HintRequest를 저장하고 HintReady 이벤트를 발행한다")
    void accept_cacheHit_savesReadyAndPublishesHintReadyEvent() {
        HintRequest saved = HintRequest.cacheHit(MEMBER_ID, PROBLEM_ID, REVISION_ID, 1, LocalDateTime.now());
        ReflectionTestUtils.setField(saved, "id", 11L);

        given(hintRequestRepository.findByMemberIdAndProblemRevisionIdAndLevel(MEMBER_ID, REVISION_ID, 1))
            .willReturn(Optional.empty());
        given(hintDailyLimitService.reserve(MEMBER_ID)).willReturn(4);
        given(valueOperations.get(anyString())).willReturn("캐시된 힌트 내용");
        given(hintRequestRepository.save(any())).willReturn(saved);

        HintAcceptResult result = sut.accept(MEMBER_ID, PROBLEM_ID, context, 1);

        assertThat(result.hintRequestId()).isEqualTo(11L);
        assertThat(result.status()).isEqualTo(HintRequestStatus.READY);
        assertThat(result.content()).isEqualTo("캐시된 힌트 내용");
        assertThat(result.todayUsedCount()).isEqualTo(4);
        verify(outboxEventWriter)
            .append(eq("HINT_REQUEST"), eq("HintReady"), anyString(), anyString(), anyString(), any());
    }
}
