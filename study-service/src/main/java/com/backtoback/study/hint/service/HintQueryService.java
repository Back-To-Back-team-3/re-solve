package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintDetailResponse;
import com.backtoback.study.hint.dto.HintHistoryItem;
import com.backtoback.study.hint.dto.HintHistoryResponse;
import com.backtoback.study.hint.repository.HintDailyUsageRepository;
import com.backtoback.study.hint.repository.HintRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 이력·단건 조회 (api-spec §6.1.2, §6.1.3).
 */
@Service
@RequiredArgsConstructor
public class HintQueryService {

    private static final List<HintRequestStatus> TERMINAL_STATUSES
        = List.of(HintRequestStatus.READY, HintRequestStatus.FALLBACK);

    private final HintRequestRepository hintRequestRepository;
    private final HintDailyUsageRepository hintDailyUsageRepository;
    private final Clock clock;

    /**
     * 회원의 문제별 힌트 이력을 조회한다 (api-spec §6.1.2).
     * 모든 회차·상태를 포함하며, 단계 오름차순·요청 시각 오름차순으로 반환한다.
     */
    @Transactional(readOnly = true)
    public HintHistoryResponse getHistory(Long memberId, Long problemId) {
        List<HintRequest> requests
            = hintRequestRepository.findAllByMemberIdAndProblemIdOrderByLevelAscCreatedAtAsc(memberId, problemId);

        List<HintHistoryItem> hints = requests.stream().map(HintHistoryItem::from).toList();

        Integer nextAvailableLevel = calculateNextAvailableLevel(requests);

        int todayUsedCount
            = hintDailyUsageRepository
                .findUsedCountByMemberIdAndUsageDate(
                    memberId,
                    LocalDate.now(clock.withZone(ClockConfiguration.SERVICE_ZONE))
                )
                .orElse(0);

        return new HintHistoryResponse(
            String.valueOf(problemId),
            hints,
            nextAvailableLevel,
            todayUsedCount,
            HintDailyLimitService.DAILY_LIMIT
        );
    }

    /**
     * 힌트 단건 상태·내용을 조회한다 (api-spec §6.1.3).
     *
     * @throws StudyException {@code HINT_REQUEST_NOT_FOUND} — 존재하지 않거나 본인 요청이 아닌 경우
     */
    @Transactional(readOnly = true)
    public HintDetailResponse getDetail(Long memberId, Long hintRequestId) {
        HintRequest request
            = hintRequestRepository
                .findByIdAndMemberId(hintRequestId, memberId)
                .orElseThrow(() -> new StudyException(StudyErrorCode.HINT_REQUEST_NOT_FOUND));

        return HintDetailResponse.from(request);
    }

    /**
     * READY·FALLBACK 단계의 최댓값 + 1을 반환한다. 최댓값이 3이면 null을 반환한다.
     */
    private Integer calculateNextAvailableLevel(List<HintRequest> requests) {
        int maxCompletedLevel
            = requests
                .stream()
                .filter(r -> TERMINAL_STATUSES.contains(r.getStatus()))
                .mapToInt(HintRequest::getLevel)
                .max()
                .orElse(0);

        if (maxCompletedLevel == 0) {
            return 1;
        }

        return maxCompletedLevel >= 3 ? null : maxCompletedLevel + 1;
    }
}
