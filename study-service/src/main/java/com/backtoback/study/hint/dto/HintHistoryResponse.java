package com.backtoback.study.hint.dto;

import java.util.List;

/**
 * 힌트 이력 조회 응답 (api-spec §6.1.2).
 */
public record HintHistoryResponse(
    String problemId,
    List<HintHistoryItem> hints,
    Integer nextAvailableLevel,
    int todayUsedCount,
    int dailyLimit
) {
}
