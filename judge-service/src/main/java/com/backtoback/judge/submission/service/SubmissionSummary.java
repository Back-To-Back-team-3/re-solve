package com.backtoback.judge.submission.service;

import java.time.LocalDateTime;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;

/**
 * 원본 코드와 실행 환경을 조회하지 않는 제출 목록의 스칼라 결과.
 *
 * @param id 제출 ID
 * @param problemId 문제 ID
 * @param language 제출 언어
 * @param context 제출 문맥
 * @param contextId 시험 또는 진단 ID; 일반 풀이는 {@code null}
 * @param status 현재 제출 상태
 * @param verdict 대표 판정
 * @param maxTimeMs 최대 실행 시간
 * @param maxMemoryKb 최대 사용 메모리
 * @param submittedAt 접수 시각
 */
public record SubmissionSummary(
    Long id,
    long problemId,
    String language,
    Submission.Context context,
    Long contextId,
    Submission.Status status,
    JudgeVerdict verdict,
    Integer maxTimeMs,
    Integer maxMemoryKb,
    LocalDateTime submittedAt
) {
}
