package com.backtoback.judge.submission.service;

import java.time.LocalDateTime;

/**
 * 일반 풀이 정답 복제본 복구에 필요한 제출 요약.
 *
 * @param id 제출 ID
 * @param problemId 문제 ID
 * @param submittedAt 최초 접수 시각
 * @param judgeAttempt 현재 완료 채점 회차
 */
public record AcceptedSubmissionSummary(Long id, long problemId, LocalDateTime submittedAt, int judgeAttempt) {
}
