package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.exception.HintInvalidStatusTransitionException;
import com.backtoback.study.hint.repository.HintRequestRepository;

/**
 * 힌트 요청 GENERATING 전이 단위 테스트 (api-spec §6.2.1, scheduler §6.1 생성 시작 시각).
 */
@ExtendWith(MockitoExtension.class)
class HintCommandServiceTest {

    private static final Clock FIXED_CLOCK
        = Clock.fixed(Instant.parse("2026-11-18T05:00:00.123456789Z"), ClockConfiguration.SERVICE_ZONE);

    @Mock
    private HintRequestRepository hintRequestRepository;

    private HintCommandService hintCommandService;

    @BeforeEach
    void setUp() {
        hintCommandService = new HintCommandService(FIXED_CLOCK, hintRequestRepository);
    }

    @Test
    @DisplayName("REQUESTED 요청을 전이하면 현재 시각(마이크로초)을 생성 시작 시각으로 넘긴다")
    void transitionToGenerating_requested_passesNowAsGenerationStartedAt() {
        given(hintRequestRepository.existsById(1L)).willReturn(true);
        given(hintRequestRepository.updateStatusToGenerating(1L, LocalDateTime.of(2026, 11, 18, 14, 0, 0, 123_456_000)))
            .willReturn(1);

        hintCommandService.transitionToGenerating(1L);

        then(hintRequestRepository)
            .should()
            .updateStatusToGenerating(1L, LocalDateTime.of(2026, 11, 18, 14, 0, 0, 123_456_000));
    }

    @Test
    @DisplayName("이미 REQUESTED가 아니어서 갱신 행이 없으면 상태 전이 예외를 던진다")
    void transitionToGenerating_notRequested_throwsInvalidTransition() {
        given(hintRequestRepository.existsById(2L)).willReturn(true);
        given(hintRequestRepository.updateStatusToGenerating(anyLong(), any(LocalDateTime.class))).willReturn(0);

        assertThatThrownBy(() -> hintCommandService.transitionToGenerating(2L))
            .isInstanceOf(HintInvalidStatusTransitionException.class);
    }

    @Test
    @DisplayName("요청이 없으면 HINT_REQUEST_NOT_FOUND를 던지고 갱신하지 않는다")
    void transitionToGenerating_notFound_throwsNotFound() {
        given(hintRequestRepository.existsById(3L)).willReturn(false);

        assertThatThrownBy(() -> hintCommandService.transitionToGenerating(3L))
            .isInstanceOf(StudyException.class)
            .extracting("errorCode")
            .isEqualTo(StudyErrorCode.HINT_REQUEST_NOT_FOUND);
        then(hintRequestRepository).should(never()).updateStatusToGenerating(anyLong(), any(LocalDateTime.class));
    }
}
