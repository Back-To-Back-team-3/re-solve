package com.backtoback.contest.exam.controller;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.contest.exam.dto.ActiveParticipationResponse;
import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.dto.ContextValidationResponse;
import com.backtoback.contest.exam.dto.ExamRejudgeEligibilityResponse;
import com.backtoback.contest.exam.dto.FinalizedResultResponse;
import com.backtoback.contest.exam.dto.GuardCompletionResponse;
import com.backtoback.contest.exam.dto.GuardFinishRequest;
import com.backtoback.contest.exam.dto.GuardPreparationResponse;
import com.backtoback.contest.exam.dto.GuardPrepareRequest;
import com.backtoback.contest.exam.dto.HintBlockResponse;
import com.backtoback.contest.exam.dto.ProblemUsageResponse;
import com.backtoback.contest.exam.dto.ShareableSubmissionResponse;
import com.backtoback.contest.exam.dto.StudyExamResponse;
import com.backtoback.contest.exam.service.InternalContestService;
import com.backtoback.contest.global.config.InternalContestHttpCondition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Member·Study·Problem·Judge에 시험·대회 내부 HTTP 계약을 제공한다. (API 명세서 §4.7)
 * 성공은 데이터 객체, 실패는 공통 오류 응답을 사용한다. (§0.3)
 * 서비스 간 인증은 내부망 신뢰 경계를 따르며 호출자 전용 헤더를 추가하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@Conditional(InternalContestHttpCondition.class)
public class InternalContestController {
    private final InternalContestService internalContestService;

    /**
     * 4.7.1 실행 문맥·열람 권한 검증.
     */
    @PostMapping("/internal/v1/contest/contexts/validate")
    public ResponseEntity<ContextValidationResponse> validateContext(
        @Valid @RequestBody ContextValidateRequest request
    ) {
        return ResponseEntity.ok(internalContestService.validateContext(request));
    }

    /**
     * 4.7.2 실제 입장·미확정 문맥 조회.
     */
    @GetMapping("/internal/v1/contest/members/{memberId}/active-participations")
    public ResponseEntity<ActiveParticipationResponse> getActiveParticipations(
        @PathVariable @NotBlank(message = "회원 ID는 필수입니다.") String memberId
    ) {
        return ResponseEntity.ok(internalContestService.getActiveParticipations(memberId));
    }

    /**
     * 4.7.3 자원 작업 준비. 진행 중 스터디 시험을 기다릴 때만 202로 응답한다.
     */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/prepare")
    public ResponseEntity<GuardPreparationResponse> prepareGuard(
        @PathVariable
        @Pattern(
            regexp = "MEMBER|STUDY|PROBLEM",
            message = "지원하는 자원 유형이 아닙니다."
        ) String resourceType,
        @PathVariable @NotBlank(message = "자원 ID는 필수입니다.") String resourceId,
        @Valid @RequestBody GuardPrepareRequest request
    ) {
        var response = internalContestService.prepareGuard(resourceType, resourceId, request);
        boolean waiting
            = "STUDY".equals(resourceType) && "PREPARED".equals(response.state())
                && "PENDING".equals(response.operationResult()) && !response.ready();
        return ResponseEntity.status(waiting ? 202 : 200).body(response);
    }

    /**
     * 4.7.4 자원 작업 완료.
     */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/complete")
    public ResponseEntity<GuardCompletionResponse> completeGuard(
        @PathVariable
        @Pattern(
            regexp = "MEMBER|STUDY|PROBLEM",
            message = "지원하는 자원 유형이 아닙니다."
        ) String resourceType,
        @PathVariable @NotBlank(message = "자원 ID는 필수입니다.") String resourceId,
        @Valid @RequestBody GuardFinishRequest request
    ) {
        return ResponseEntity.ok(internalContestService.completeGuard(resourceType, resourceId, request));
    }

    /**
     * 4.7.4 자원 작업 취소.
     */
    @PostMapping("/internal/v1/contest/guards/{resourceType}/{resourceId}/cancel")
    public ResponseEntity<GuardCompletionResponse> cancelGuard(
        @PathVariable
        @Pattern(
            regexp = "MEMBER|STUDY|PROBLEM",
            message = "지원하는 자원 유형이 아닙니다."
        ) String resourceType,
        @PathVariable @NotBlank(message = "자원 ID는 필수입니다.") String resourceId,
        @Valid @RequestBody GuardFinishRequest request
    ) {
        return ResponseEntity.ok(internalContestService.cancelGuard(resourceType, resourceId, request));
    }

    /**
     * 4.7.5 스터디 시험 상태 조회.
     */
    @GetMapping("/internal/v1/contest/studies/{studyId}/exams")
    public ResponseEntity<StudyExamResponse> getStudyExams(
        @PathVariable @NotBlank(message = "스터디 ID는 필수입니다.") String studyId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20")
        @Min(
            value = 1,
            message = "페이지 크기는 1 이상이어야 합니다."
        )
        @Max(
            value = 100,
            message = "페이지 크기는 100 이하여야 합니다."
        ) int size
    ) {
        return ResponseEntity.ok(internalContestService.getStudyExams(studyId, after, size));
    }

    /**
     * 4.7.6 고정 문제 사용 조회.
     */
    @GetMapping("/internal/v1/contest/problems/{problemId}/usages")
    public ResponseEntity<ProblemUsageResponse> getProblemUsages(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId
    ) {
        return ResponseEntity.ok(internalContestService.getProblemUsages(problemId));
    }

    /**
     * 4.7.7 힌트 차단 복구 조회.
     */
    @GetMapping("/internal/v1/contest/hint-blocks")
    public ResponseEntity<HintBlockResponse> getHintBlocks(
        @RequestParam @NotBlank(message = "회원 ID는 필수입니다.") String memberId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20")
        @Min(
            value = 1,
            message = "페이지 크기는 1 이상이어야 합니다."
        )
        @Max(
            value = 100,
            message = "페이지 크기는 100 이하여야 합니다."
        ) int size
    ) {
        return ResponseEntity.ok(internalContestService.getHintBlocks(memberId, after, size));
    }

    /**
     * 4.7.8 제출 공유 스냅샷 조회. 불변 코드 응답의 캐시를 금지한다.
     */
    @GetMapping("/internal/v1/contest/submissions/{context}/{receiptId}/shareable")
    public ResponseEntity<ShareableSubmissionResponse> getShareableSubmission(
        @PathVariable
        @Pattern(
            regexp = "EXAM|CONTEST",
            message = "지원하는 문맥이 아닙니다."
        ) String context,
        @PathVariable @NotBlank(message = "접수 ID는 필수입니다.") String receiptId,
        @RequestParam @NotBlank(message = "회원 ID는 필수입니다.") String memberId
    ) {
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(internalContestService.getShareableSubmission(context, receiptId, memberId));
    }

    /**
     * 4.7.9 확정 결과 스냅샷 조회.
     */
    @GetMapping("/internal/v1/contest/results/{context}/{contextId}")
    public ResponseEntity<FinalizedResultResponse> getFinalizedResult(
        @PathVariable
        @Pattern(
            regexp = "EXAM|CONTEST",
            message = "지원하는 문맥이 아닙니다."
        ) String context,
        @PathVariable @NotBlank(message = "문맥 ID는 필수입니다.") String contextId,
        @RequestParam(required = false) String resultRevision,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20")
        @Min(
            value = 1,
            message = "페이지 크기는 1 이상이어야 합니다."
        )
        @Max(
            value = 100,
            message = "페이지 크기는 100 이하여야 합니다."
        ) int size
    ) {
        return ResponseEntity
            .ok(internalContestService.getFinalizedResult(context, contextId, resultRevision, after, size));
    }

    /**
     * 4.7.10 시험 재채점 가능 여부 조회. 기존 exams 경로와 세 응답 필드만 유지한다.
     */
    @GetMapping("/internal/v1/exams/{examId}/rejudge-eligibility")
    public ResponseEntity<ExamRejudgeEligibilityResponse> getRejudgeEligibility(
        @PathVariable @NotBlank(message = "시험 ID는 필수입니다.") String examId
    ) {
        return ResponseEntity.ok(internalContestService.getRejudgeEligibility(examId));
    }
}
