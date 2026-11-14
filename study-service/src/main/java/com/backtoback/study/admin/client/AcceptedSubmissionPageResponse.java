package com.backtoback.study.admin.client;

import java.time.OffsetDateTime;
import java.util.List;

/** judge-service §3.3.4 회원별 PRACTICE AC 이력 한 페이지. */
public record AcceptedSubmissionPageResponse(List<Item> items, String nextCursor) {
    /** 복제본 재동기화에 필요한 정답 제출의 식별 정보. */
    public record Item(String submissionId, String problemId, OffsetDateTime submittedAt, int judgeAttempt) {
    }
}
