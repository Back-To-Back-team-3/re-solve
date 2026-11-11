package com.backtoback.study.hint.dto;

import com.backtoback.study.hint.domain.HintRequestStatus;

public record HintAcceptResult(long hintRequestId, HintRequestStatus status, String content, int todayUsedCount) {
}
