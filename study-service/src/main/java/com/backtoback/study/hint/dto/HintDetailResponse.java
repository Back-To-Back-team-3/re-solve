package com.backtoback.study.hint.dto;

import java.time.LocalDateTime;

import com.backtoback.study.hint.domain.HintRequest;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 힌트 단건 조회 응답 (api-spec §6.1.3).
 * {@code BLOCKED} 상태이면 {@code content}는 {@code null}이고 {@code blockedReason}이 채워진다.
 */
public record HintDetailResponse(
    String hintRequestId,
    String problemId,
    int level,
    HintRequestStatus status,
    @JsonProperty("isFallback") boolean fallback,
    String blockedReason,
    String content,
    LocalDateTime completedAt
) {

    public static HintDetailResponse from(HintRequest request) {
        HintRequestStatus status = request.getStatus();
        // BLOCKED 상태이면 content를 노출하지 않는다 (api-spec §6.1.3).
        String content = status == HintRequestStatus.BLOCKED ? null : request.getContent();

        return new HintDetailResponse(
            String.valueOf(request.getId()),
            String.valueOf(request.getProblemId()),
            request.getLevel(),
            status,
            request.isFallback(),
            request.getBlockedReason(),
            content,
            request.getCompletedAt()
        );
    }
}
