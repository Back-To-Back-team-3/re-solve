package com.backtoback.judge.admin.service.dto;

import java.time.LocalDateTime;

/** 관리자 조회에 필요한 채점 작업의 스칼라 상태. 원본 코드·테스트 자료는 제외한다. */
public record JudgeJobState(
    long id,
    long submissionId,
    int judgeAttempt,
    String status,
    int tryCount,
    String failureCode,
    long memberId,
    String context,
    Long contextId,
    long revisionId,
    LocalDateTime deadlineAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime leaseUntil,
    LocalDateTime nextRunAt
) {
}
