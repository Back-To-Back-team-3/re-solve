package com.backtoback.judge.submission.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.dto.AcceptedSubmissionHistoryResponse;
import com.backtoback.judge.submission.dto.InternalSubmissionResultResponse;
import com.backtoback.judge.submission.dto.InternalSubmissionSourceResponse;
import com.backtoback.judge.submission.dto.SubmissionReceiptResponse;
import com.backtoback.judge.submission.service.InternalSubmissionQueryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/** 시험·대회·진단 제출 대사, 복기와 일반 풀이 AC 이력 조회 내부 API. */
@RestController
@RequiredArgsConstructor
public class InternalSubmissionQueryController {
    private final InternalSubmissionQueryService queries;

    /**
     * 회원별 일반 풀이 정답 이력을 커서로 조회한다. (API 명세서 §3.3.4)
     * 잘못된 문맥·식별자·크기는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param context 일반 풀이를 뜻하는 {@code PRACTICE}
     * @param userId 재동기화할 회원 ID
     * @param after 마지막 제출 ID; 첫 페이지에서는 생략
     * @param size 페이지 크기; 생략하면 100
     * @return 공통 성공 래퍼 없이 반환하는 정답 이력 페이지
     */
    @GetMapping("/internal/v1/submissions/accepted")
    public ResponseEntity<AcceptedSubmissionHistoryResponse> acceptedHistory(
        @RequestParam
        @Pattern(
            regexp = "PRACTICE",
            message = "재동기화 문맥은 PRACTICE여야 합니다."
        ) String context,
        @RequestParam @SubmissionQueryId String userId,
        @RequestParam(required = false) @SubmissionQueryId String after,
        @RequestParam(defaultValue = "100")
        @Min(
            value = 1,
            message = "페이지 크기는 1 이상이어야 합니다."
        )
        @Max(
            value = 500,
            message = "페이지 크기는 500 이하여야 합니다."
        ) int size
    ) {
        long afterId = after == null ? 0L : Long.parseLong(after);
        var history = queries.acceptedHistory(Long.parseLong(userId), afterId, size);
        return ResponseEntity.ok(AcceptedSubmissionHistoryResponse.from(history));
    }

    /**
     * Judge 제출 ID와 시험·대회·진단 ID가 일치하는 제출 상태를 조회한다. (API 명세서 §3.3.1)
     * 잘못된 식별자는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param submissionId Judge 제출 ID
     * @param contextId 원래 시험·대회·진단 ID
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
     * 본인 시험·진단 제출의 복기용 원본 코드를 조회한다. (API 명세서 §3.3.2)
     * 잘못된 식별자는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param submissionId Judge 제출 ID
     * @param purpose 시험 또는 진단 복기 목적
     * @param memberId 문맥 소유 서비스에서 인증한 요청자 ID
     * @param contextId 시험 또는 진단 ID
     * @return 캐시할 수 없는 원본 코드 응답
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 제출이 없거나 소유자·문맥·문맥 ID가 일치하지 않는 경우
     */
    @GetMapping(
        value = "/internal/v1/submissions/{submissionId}/source",
        params = "purpose"
    )
    public ResponseEntity<InternalSubmissionSourceResponse> reviewSource(
        @PathVariable @SubmissionQueryId String submissionId,
        @RequestParam
        @Pattern(
            regexp = "EXAM_REVIEW|DIAGNOSIS_REVIEW",
            message = "복기 목적은 EXAM_REVIEW 또는 DIAGNOSIS_REVIEW여야 합니다."
        ) String purpose,
        @RequestParam @SubmissionQueryId String memberId,
        @RequestParam @SubmissionQueryId String contextId
    ) {
        var context = "EXAM_REVIEW".equals(purpose) ? Submission.Context.EXAM : Submission.Context.DIAGNOSIS;
        var submission
            = queries
                .reviewSource(
                    Long.parseLong(submissionId),
                    Long.parseLong(memberId),
                    context,
                    Long.parseLong(contextId)
                );
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(InternalSubmissionSourceResponse.from(submission));
    }

    /**
     * 원래 접수 ID로 현재 채점 상태를 조회한다. (API 명세서 §3.3.6)
     * 잘못된 문맥이나 접수 ID는 요청 검증 단계에서 {@code COMMON_INVALID_REQUEST}로 응답한다.
     *
     * @param context {@code EXAM}, {@code CONTEST} 또는 {@code DIAGNOSIS}
     * @param receiptId Contest 또는 Member의 원래 접수 ID
     * @return 미접수 여부 또는 연결된 제출의 원본 식별자와 현재 상태
     */
    @GetMapping("/internal/v1/judge/submissions/by-receipt")
    public ResponseEntity<SubmissionReceiptResponse> byReceipt(
        @RequestParam
        @Pattern(
            regexp = "EXAM|CONTEST|DIAGNOSIS",
            message = "제출 문맥은 EXAM, CONTEST 또는 DIAGNOSIS여야 합니다."
        ) String context,
        @RequestParam @SubmissionQueryId String receiptId
    ) {
        var submission = queries.byReceipt(Submission.Context.valueOf(context), Long.parseLong(receiptId));
        return ResponseEntity.ok(SubmissionReceiptResponse.from(submission));
    }
}
