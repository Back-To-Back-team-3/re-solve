package com.backtoback.study.hint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintDetailResponse;
import com.backtoback.study.hint.dto.HintHistoryResponse;
import com.backtoback.study.hint.repository.HintDailyUsageRepository;
import com.backtoback.study.hint.repository.HintRequestRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HintQueryServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long PROBLEM_ID = 100L;
    private static final Long HINT_REQUEST_ID = 10L;

    @Mock
    private HintRequestRepository hintRequestRepository;

    @Mock
    private HintDailyUsageRepository hintDailyUsageRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private HintQueryService hintQueryService;

    @BeforeEach
    void setUpClock() {
        given(clock.withZone(ClockConfiguration.SERVICE_ZONE)).willReturn(Clock.systemDefaultZone());
    }

    // --- getHistory ---

    @Test
    @DisplayName("힌트 이력이 없으면 hints가 빈 배열이고 nextAvailableLevel은 1을 반환한다")
    void getHistory_empty() {
        given(hintRequestRepository.findAllByMemberIdAndProblemIdOrderByLevelAscCreatedAtAsc(MEMBER_ID, PROBLEM_ID))
            .willReturn(List.of());
        given(hintDailyUsageRepository.findUsedCountByMemberIdAndUsageDate(eq(MEMBER_ID), any(LocalDate.class)))
            .willReturn(Optional.of(0));

        HintHistoryResponse response = hintQueryService.getHistory(MEMBER_ID, PROBLEM_ID);

        assertThat(response.hints()).isEmpty();
        assertThat(response.nextAvailableLevel()).isEqualTo(1);
        assertThat(response.todayUsedCount()).isZero();
        assertThat(response.dailyLimit()).isEqualTo(HintDailyLimitService.DAILY_LIMIT);
    }

    @Test
    @DisplayName("level 1·2가 READY이면 nextAvailableLevel은 3이다")
    void getHistory_level1And2Ready_nextLevelIs3() {
        HintRequest req1 = stubRequest(1L, 1, HintRequestStatus.READY);
        HintRequest req2 = stubRequest(2L, 2, HintRequestStatus.READY);
        given(hintRequestRepository.findAllByMemberIdAndProblemIdOrderByLevelAscCreatedAtAsc(MEMBER_ID, PROBLEM_ID))
            .willReturn(List.of(req1, req2));
        given(hintDailyUsageRepository.findUsedCountByMemberIdAndUsageDate(eq(MEMBER_ID), any(LocalDate.class)))
            .willReturn(Optional.empty());

        HintHistoryResponse response = hintQueryService.getHistory(MEMBER_ID, PROBLEM_ID);

        assertThat(response.nextAvailableLevel()).isEqualTo(3);
        assertThat(response.todayUsedCount()).isZero();
    }

    @Test
    @DisplayName("level 1·2·3이 READY이면 nextAvailableLevel은 null이다")
    void getHistory_allLevelsReady_nextLevelIsNull() {
        HintRequest req1 = stubRequest(1L, 1, HintRequestStatus.READY);
        HintRequest req2 = stubRequest(2L, 2, HintRequestStatus.READY);
        HintRequest req3 = stubRequest(3L, 3, HintRequestStatus.READY);
        given(hintRequestRepository.findAllByMemberIdAndProblemIdOrderByLevelAscCreatedAtAsc(MEMBER_ID, PROBLEM_ID))
            .willReturn(List.of(req1, req2, req3));
        given(hintDailyUsageRepository.findUsedCountByMemberIdAndUsageDate(eq(MEMBER_ID), any(LocalDate.class)))
            .willReturn(Optional.of(3));

        HintHistoryResponse response = hintQueryService.getHistory(MEMBER_ID, PROBLEM_ID);

        assertThat(response.nextAvailableLevel()).isNull();
    }

    @Test
    @DisplayName("BLOCKED 상태를 포함한 모든 이력이 반환된다")
    void getHistory_includesBlockedStatus() {
        HintRequest ready = stubRequest(1L, 1, HintRequestStatus.READY);
        HintRequest blocked = stubRequest(2L, 1, HintRequestStatus.BLOCKED);
        given(hintRequestRepository.findAllByMemberIdAndProblemIdOrderByLevelAscCreatedAtAsc(MEMBER_ID, PROBLEM_ID))
            .willReturn(List.of(ready, blocked));
        given(hintDailyUsageRepository.findUsedCountByMemberIdAndUsageDate(eq(MEMBER_ID), any(LocalDate.class)))
            .willReturn(Optional.empty());

        HintHistoryResponse response = hintQueryService.getHistory(MEMBER_ID, PROBLEM_ID);

        assertThat(response.hints()).hasSize(2);
    }

    // --- getDetail ---

    @Test
    @DisplayName("존재하지 않는 hintRequestId로 조회하면 HINT_REQUEST_NOT_FOUND 예외가 발생한다")
    void getDetail_notFound_throwsException() {
        given(hintRequestRepository.findByIdAndMemberId(HINT_REQUEST_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> hintQueryService.getDetail(MEMBER_ID, HINT_REQUEST_ID))
            .isInstanceOf(StudyException.class)
            .hasMessageContaining(StudyErrorCode.HINT_REQUEST_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("BLOCKED 상태 단건 조회 시 content는 null이고 blockedReason을 반환한다")
    void getDetail_blocked_contentIsNull() {
        HintRequest request = stubBlockedRequest(HINT_REQUEST_ID, 1, "EXAM");
        given(hintRequestRepository.findByIdAndMemberId(HINT_REQUEST_ID, MEMBER_ID)).willReturn(Optional.of(request));

        HintDetailResponse response = hintQueryService.getDetail(MEMBER_ID, HINT_REQUEST_ID);

        assertThat(response.content()).isNull();
        assertThat(response.blockedReason()).isEqualTo("EXAM");
    }

    @Test
    @DisplayName("READY 상태 단건 조회 시 content가 반환된다")
    void getDetail_ready_contentReturned() {
        HintRequest request = stubReadyRequest(HINT_REQUEST_ID, 1, "힌트 내용");
        given(hintRequestRepository.findByIdAndMemberId(HINT_REQUEST_ID, MEMBER_ID)).willReturn(Optional.of(request));

        HintDetailResponse response = hintQueryService.getDetail(MEMBER_ID, HINT_REQUEST_ID);

        assertThat(response.content()).isEqualTo("힌트 내용");
        assertThat(response.blockedReason()).isNull();
    }

    private HintRequest stubRequest(Long id, int level, HintRequestStatus status) {
        HintRequest request = mock(HintRequest.class);
        given(request.getId()).willReturn(id);
        given(request.getMemberId()).willReturn(MEMBER_ID);
        given(request.getProblemId()).willReturn(PROBLEM_ID);
        given(request.getLevel()).willReturn(level);
        given(request.getStatus()).willReturn(status);
        given(request.isFallback()).willReturn(false);
        return request;
    }

    private HintRequest stubBlockedRequest(Long id, int level, String blockedReason) {
        HintRequest request = stubRequest(id, level, HintRequestStatus.BLOCKED);
        given(request.getBlockedReason()).willReturn(blockedReason);
        given(request.getContent()).willReturn(null);
        return request;
    }

    private HintRequest stubReadyRequest(Long id, int level, String content) {
        HintRequest request = stubRequest(id, level, HintRequestStatus.READY);
        given(request.getContent()).willReturn(content);
        given(request.getBlockedReason()).willReturn(null);
        return request;
    }
}
