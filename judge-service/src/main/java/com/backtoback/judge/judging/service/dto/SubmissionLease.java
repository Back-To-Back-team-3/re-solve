package com.backtoback.judge.judging.service.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/** 채점 회차와 실행 시도를 함께 식별하는 선점 소유권. */
public record SubmissionLease(
    long jobId,
    long submissionId,
    int judgeAttempt,
    int tryCount,
    String token,
    LocalDateTime deadlineAt
) {
    /**
     * 실행 로그와 이벤트에 같은 스케줄러 추적 ID를 제공한다. 임대 토큰 원문은 노출하지 않는다.
     * 임대가 없는 대기 작업의 종결은 호출마다 별도 추적 ID를 생성한다. (이벤트 계약 §1.1)
     *
     * @return 실행 임대 또는 대기 작업 종결의 추적 ID
     */
    public String correlationId() {
        if (token == null) {
            return "sched-JudgeJobExpiry-" + UUID.randomUUID();
        }
        return "sched-JudgeJobExecutor-" + UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8));
    }
}
