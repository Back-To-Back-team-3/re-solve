package com.backtoback.problem.problem.controller;

import java.util.List;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemServiceCondition;
import com.backtoback.problem.problem.dto.request.ContestProblemLinkRequest;
import com.backtoback.problem.problem.dto.response.ContestProblemLinkResponse;
import com.backtoback.problem.problem.dto.response.JudgingDataResponse;
import com.backtoback.problem.problem.dto.response.JudgingValidationResponse;
import com.backtoback.problem.problem.dto.response.ProblemContentResponse;
import com.backtoback.problem.problem.dto.response.ProblemOperationResponse;
import com.backtoback.problem.problem.dto.response.ProblemSummaryListResponse;
import com.backtoback.problem.problem.dto.response.ValidationDataResponse;
import com.backtoback.problem.problem.service.InternalProblemService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/**
 * API 명세서 2.6.1~2.6.5·2.6.8~2.6.10의 내부 HTTP API를 제공한다.
 */
@Validated
@RestController
@Conditional(InternalProblemServiceCondition.class)
@RequiredArgsConstructor
public class InternalProblemController {
    private static final String PROBLEM_PATH = "/internal/v1/problems";

    private final InternalProblemService internalProblemService;

    /** 채점 대상의 고정 회차 검증 결과를 제공한다. (API 명세서 2.6.1) */
    @GetMapping(PROBLEM_PATH + "/{problemId}/revisions/{revisionId}/judging/validation")
    public JudgingValidationResponse getJudgingValidation(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
        @PathVariable @NotBlank(message = "회차 ID는 필수입니다.") String revisionId
    ) {
        return internalProblemService.getJudgingValidation(problemId, revisionId);
    }

    /** 채점 실행에 필요한 고정 회차 자료 참조를 제공한다. (API 명세서 2.6.2) */
    @GetMapping(PROBLEM_PATH + "/{problemId}/revisions/{revisionId}/judging/data")
    public JudgingDataResponse getJudgingData(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
        @PathVariable @NotBlank(message = "회차 ID는 필수입니다.") String revisionId
    ) {
        return internalProblemService.getJudgingData(problemId, revisionId);
    }

    /** 호출 문맥이 제공한 고정 회차 본문을 반환한다. (API 명세서 2.6.3) */
    @GetMapping(PROBLEM_PATH + "/{problemId}/revisions/{revisionId}/content")
    public ProblemContentResponse getContent(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
        @PathVariable @NotBlank(message = "회차 ID는 필수입니다.") String revisionId,
        @RequestHeader("X-Access-Context") @NotBlank(message = "열람 문맥은 필수입니다.") String accessContext
    ) {
        return internalProblemService.getContent(problemId, revisionId, accessContext);
    }

    /** 회차 본문과 연결된 개발용 이미지 바이트를 반환한다. (API 명세서 2.6.4) */
    @GetMapping(PROBLEM_PATH + "/{problemId}/revisions/{revisionId}/images/{imageId}")
    public ResponseEntity<byte[]> getImage(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
        @PathVariable @NotBlank(message = "회차 ID는 필수입니다.") String revisionId,
        @PathVariable @NotBlank(message = "이미지 ID는 필수입니다.") String imageId,
        @RequestHeader("X-Access-Context") @NotBlank(message = "열람 문맥은 필수입니다.") String accessContext
    ) {
        byte[] image = internalProblemService.getImage(problemId, revisionId, imageId, accessContext);
        return ResponseEntity
            .ok()
            .contentType(MediaType.valueOf("image/svg+xml"))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(image);
    }

    /** 요청한 문제 ID마다 편입 요약을 반환한다. (API 명세서 2.6.5) */
    @GetMapping(PROBLEM_PATH + "/summaries")
    public ProblemSummaryListResponse getSummaries(
        @RequestParam List<String> ids,
        @RequestParam @NotBlank(message = "용도는 필수입니다.") String purpose
    ) {
        return internalProblemService.getSummaries(ids, purpose);
    }

    /** 관리자 검증 요청에 고정된 코드와 테스트 자료를 반환한다. (API 명세서 2.6.8) */
    @GetMapping(PROBLEM_PATH + "/validations/{validationId}/data")
    public ValidationDataResponse getValidationData(
        @PathVariable @NotBlank(message = "검증 ID는 필수입니다.") String validationId,
        @RequestHeader("X-Correlation-Id") @NotBlank(message = "상관관계 ID는 필수입니다.") String correlationId
    ) {
        return internalProblemService.getValidationData(validationId);
    }

    /** 대회 전용 문제 연결 요청의 고정 성공·충돌 결과를 반환한다. (API 명세서 2.6.9) */
    @PutMapping(PROBLEM_PATH + "/{problemId}/contest")
    public ContestProblemLinkResponse linkContestProblem(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
        @Valid @RequestBody ContestProblemLinkRequest request
    ) {
        return internalProblemService.linkContestProblem(problemId, request);
    }

    /** 문제 상태 작업의 원래 결과 스냅샷을 반환한다. (API 명세서 2.6.10) */
    @GetMapping(PROBLEM_PATH + "/{problemId}/operations/{operationId}")
    public ProblemOperationResponse getOperation(
        @PathVariable @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
        @PathVariable @NotBlank(message = "작업 ID는 필수입니다.") String operationId
    ) {
        return internalProblemService.getOperation(problemId, operationId);
    }
}
