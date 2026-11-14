package com.backtoback.study.hint.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 힌트 이력 항목 (api-spec §6.1.2 hints[]).
 */
public record HintHistoryItem(
    String hintRequestId,
    int level,
    HintRequestStatus status,
    @JsonProperty("isFallback") boolean fallback,
    String content,
    OffsetDateTime requestedAt,
    OffsetDateTime completedAt
) {

    public static HintHistoryItem from(HintRequest request) {
        return new HintHistoryItem(
            String.valueOf(request.getId()),
            request.getLevel(),
            request.getStatus(),
            request.isFallback(),
            request.getContent(),
            toSeoulOffset(request.getCreatedAt()),
            toSeoulOffset(request.getCompletedAt())
        );
    }

    private static OffsetDateTime toSeoulOffset(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }
}
