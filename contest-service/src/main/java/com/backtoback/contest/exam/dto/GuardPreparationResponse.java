package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 자원 준비 상태. ready=false인 스터디 진행 시험 응답은 202를 사용한다. (API 명세서 §4.7.3)
 */
public record GuardPreparationResponse(
    String operationId,
    String state,
    String operationResult,
    boolean ready,
    OffsetDateTime preparedAt,
    List<String> canceledExamIds,
    List<String> blockingExamIds
) {
}
