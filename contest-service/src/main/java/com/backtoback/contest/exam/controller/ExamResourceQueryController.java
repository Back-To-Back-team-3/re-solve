package com.backtoback.contest.exam.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.contest.exam.dto.ActiveParticipationResponse;
import com.backtoback.contest.exam.dto.ExamRejudgeEligibilityResponse;
import com.backtoback.contest.exam.dto.HintBlockResponse;
import com.backtoback.contest.exam.dto.ProblemUsageResponse;
import com.backtoback.contest.exam.service.ExamResourceQueryService;

import lombok.RequiredArgsConstructor;

/** 내부망에 시험 자원 사용과 복구 상태를 제공한다. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "contest.internal-http.mode",
    havingValue = "real"
)
public class ExamResourceQueryController {
    private final ExamResourceQueryService service;

    /** API 명세서 §4.7.2 실제 입장·미확정 문맥 조회. */
    @GetMapping("/internal/v1/contest/members/{memberId}/active-participations")
    public ResponseEntity<ActiveParticipationResponse> active(@PathVariable String memberId) {
        return ResponseEntity.ok(service.active(memberId));
    }

    /** API 명세서 §4.7.6 고정 문제 사용 조회. */
    @GetMapping("/internal/v1/contest/problems/{problemId}/usages")
    public ResponseEntity<ProblemUsageResponse> usage(@PathVariable String problemId) {
        return ResponseEntity.ok(service.usage(problemId));
    }

    /** API 명세서 §4.7.7 힌트 차단 복구 조회. */
    @GetMapping("/internal/v1/contest/hint-blocks")
    public ResponseEntity<HintBlockResponse> hintBlocks(
        @RequestParam String memberId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(service.hintBlocks(memberId, after, size));
    }

    /** API 명세서 §4.7.10 시험 재채점 가능 여부 조회. */
    @GetMapping("/internal/v1/exams/{examId}/rejudge-eligibility")
    public ResponseEntity<ExamRejudgeEligibilityResponse> eligibility(@PathVariable String examId) {
        return ResponseEntity.ok(service.eligibility(examId));
    }
}
