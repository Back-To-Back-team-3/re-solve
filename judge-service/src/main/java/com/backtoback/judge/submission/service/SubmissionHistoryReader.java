package com.backtoback.judge.submission.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.repository.JudgeJobRepository;
import com.backtoback.judge.judging.repository.JudgeTestResultRepository;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** 상태와 테스트 결과를 같은 DB 스냅샷에서 읽어 종결 중간 상태를 노출하지 않는다. */
@Service
@RequiredArgsConstructor
public class SubmissionHistoryReader {
    private final SubmissionRepository submissions;
    private final JudgeJobRepository jobs;
    private final JudgeTestResultRepository tests;

    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
    )
    public SubmissionHistory read(long memberId, long submissionId) {
        var submission
            = submissions
                .findByIdAndMemberIdAndContext(submissionId, memberId, Submission.Context.PRACTICE)
                .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
        var attempts = jobs.findBySubmissionIdOrderByJudgeAttemptDesc(submissionId);
        var results
            = tests
                .findByJudgeJobSubmissionIdOrderByJudgeJobJudgeAttemptAscTestOrderAsc(submissionId)
                .stream()
                .collect(Collectors.groupingBy(value -> value.getJudgeJob().getId()));
        return new SubmissionHistory(
            submission,
            attempts
                .stream()
                .map(
                    job -> new SubmissionHistory.Attempt(job, List.copyOf(results.getOrDefault(job.getId(), List.of())))
                )
                .toList()
        );
    }
}
