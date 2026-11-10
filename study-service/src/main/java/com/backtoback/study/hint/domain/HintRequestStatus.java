package com.backtoback.study.hint.domain;

/**
 * 힌트 요청 상태(ERD 4.6.1 hint_requests.status).
 */
public enum HintRequestStatus {
    REQUESTED, GENERATING, READY, FALLBACK, BLOCKED
}
