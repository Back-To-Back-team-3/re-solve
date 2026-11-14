package com.backtoback.problem.problem.controller;

import org.springframework.context.annotation.Conditional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemStubCondition;
import com.backtoback.problem.problem.dto.response.ValidationDataResponse;
import com.backtoback.problem.problem.service.InternalProblemService;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/** 명시적으로 선택한 Stub 환경에서 검증 자료 예시를 제공한다. */
@Validated
@RestController
@Conditional(InternalProblemStubCondition.class)
@RequiredArgsConstructor
public class ProblemValidationStubController {
    private final InternalProblemService service;

    /**
     * 개발용 검증 자료를 반환한다. API 명세서 §2.6.8을 따른다.
     *
     * @param validationId 검증 요청 ID
     * @param correlationId 호출 추적 ID
     * @return 개발용 고정 자료
     */
    @GetMapping("/internal/v1/problems/validations/{validationId}/data")
    public ValidationDataResponse read(
        @PathVariable @NotBlank(message = "검증 ID는 필수입니다.") String validationId,
        @RequestHeader("X-Correlation-Id") @NotBlank(message = "상관관계 ID는 필수입니다.") String correlationId
    ) {
        return service.getValidationData(validationId);
    }
}
