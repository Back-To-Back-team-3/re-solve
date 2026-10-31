package com.backtoback.judge.submission.service;

import java.util.List;

import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.submission.domain.Submission;

/** 하나의 읽기 트랜잭션에서 확보한 제출·회차·테스트 결과. 외부 응답에는 DTO로 변환한다. */
public record SubmissionHistory(Submission submission, List<Attempt> attempts) {
    public record Attempt(JudgeJob job, List<JudgeTestResult> tests) {
    }
}
