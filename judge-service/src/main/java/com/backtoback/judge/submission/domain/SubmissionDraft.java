package com.backtoback.judge.submission.domain;

import java.time.LocalDateTime;

/**
 * 외부 검증을 마친 제출 원본을 저장 계층에 전달한다.
 *
 * @param memberId 제출자 ID
 * @param problemId 문제 ID
 * @param problemRevisionId 고정 문제 회차 ID
 * @param context 제출 문맥
 * @param contextId 시험·대회·진단 문맥 ID; 일반 풀이는 {@code null}
 * @param sourceSubmissionId 외부 접수 ID; 일반 풀이는 {@code null}
 * @param idempotencyKey 일반 풀이 멱등 키; 다른 문맥은 {@code null}
 * @param requestHash 일반 풀이 원본 요청의 SHA-256; 다른 문맥은 {@code null}
 * @param language 서비스 언어 코드
 * @param sourceCode 불변 제출 코드
 * @param submittedAt 서버 접수 시각; 외부 접수는 원본 시각
 */
public record SubmissionDraft(
    long memberId,
    long problemId,
    long problemRevisionId,
    Submission.Context context,
    Long contextId,
    Long sourceSubmissionId,
    String idempotencyKey,
    String requestHash,
    String language,
    String sourceCode,
    LocalDateTime submittedAt
) {
}
