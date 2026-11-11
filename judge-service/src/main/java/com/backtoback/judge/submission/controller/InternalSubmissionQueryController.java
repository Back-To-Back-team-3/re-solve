package com.backtoback.judge.submission.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.dto.ExamSubmissionSourceResponse;
import com.backtoback.judge.submission.dto.InternalSubmissionResultResponse;
import com.backtoback.judge.submission.dto.SubmissionReceiptResponse;
import com.backtoback.judge.submission.service.InternalSubmissionQueryService;

import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/** Contest의 시험·대회 제출 대사와 시험 복기 내부 API. */
@RestController
@RequiredArgsConstructor
public class InternalSubmissionQueryController {
    private final InternalSubmissionQueryService queries;

    /**
     * Judge 제출 ID와 시험·대회 ID가 일치하는 제출 상태를 조회한다. (API 명세서 §3.3.1)
     * 잘못된 식별자는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param submissionId Judge 제출 ID
     * @param contextId Contest의 시험·대회 ID
     * @return 공통 성공 래퍼 없이 반환하는 현재 제출 결과
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 해당 문맥의 제출이 없는 경우
     */
    @GetMapping("/internal/v1/submissions/{submissionId}/result")
    public ResponseEntity<InternalSubmissionResultResponse> result(
        @PathVariable @SubmissionQueryId String submissionId,
        @RequestParam @SubmissionQueryId String contextId
    ) {
        var submission = queries.result(Long.parseLong(submissionId), Long.parseLong(contextId));
        return ResponseEntity.ok(InternalSubmissionResultResponse.from(submission));
    }

    /**
     * 본인 시험 제출의 복기용 원본 코드를 조회한다. (API 명세서 §3.3.2)
     * 잘못된 식별자는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param submissionId Judge 제출 ID
     * @param memberId Contest가 인증한 요청자 ID
     * @param contextId Contest의 시험 ID
     * @return 캐시할 수 없는 원본 코드 응답
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 제출이 없거나 소유자·시험 ID가 일치하지 않는 경우
     */
    @GetMapping(
        value = "/internal/v1/submissions/{submissionId}/source",
        params = "purpose=EXAM_REVIEW"
    )
    public ResponseEntity<ExamSubmissionSourceResponse> examSource(
        @PathVariable @SubmissionQueryId String submissionId,
        @RequestParam @SubmissionQueryId String memberId,
        @RequestParam @SubmissionQueryId String contextId
    ) {
        var submission
            = queries.examSource(Long.parseLong(submissionId), Long.parseLong(memberId), Long.parseLong(contextId));
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(ExamSubmissionSourceResponse.from(submission));
    }

    /**
     * 원래 접수 ID로 현재 채점 상태를 조회한다. (API 명세서 §3.3.6)
     * 잘못된 문맥이나 접수 ID는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param context {@code EXAM} 또는 {@code CONTEST}
     * @param receiptId Contest의 원래 접수 ID
     * @return 미접수 여부 또는 연결된 제출의 원본 식별자와 현재 상태
     */
    @GetMapping("/internal/v1/judge/submissions/by-receipt")
    public ResponseEntity<SubmissionReceiptResponse> byReceipt(
        @RequestParam
        @Pattern(
            regexp = "EXAM|CONTEST",
            message = "제출 문맥은 EXAM 또는 CONTEST여야 합니다."
        ) String context,
        @RequestParam @SubmissionQueryId String receiptId
    ) {
        var submission = queries.byReceipt(Submission.Context.valueOf(context), Long.parseLong(receiptId));
        return ResponseEntity.ok(SubmissionReceiptResponse.from(submission));
    }
}
