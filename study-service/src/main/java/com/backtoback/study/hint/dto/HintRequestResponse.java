package com.backtoback.study.hint.dto;

public record HintRequestResponse(
    String hintRequestId,
    String status,
    String content,
    int todayUsedCount,
    int dailyLimit
) {
}
