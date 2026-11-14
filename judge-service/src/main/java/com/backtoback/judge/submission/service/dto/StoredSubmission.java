package com.backtoback.judge.submission.service.dto;

/**
 * 원자적으로 저장한 제출과 작업의 식별자를 반환한다.
 *
 * @param submissionId 제출 ID
 * @param judgeJobId 첫 채점 작업 ID
 * @param judgeAttempt 최초 채점 회차
 */
public record StoredSubmission(long submissionId, long judgeJobId, int judgeAttempt) {
}
