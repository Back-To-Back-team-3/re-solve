package com.backtoback.problem.problem.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.dto.response.ValidationDataResponse;
import com.backtoback.problem.problem.service.ProblemValidationDataService;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/**
 * Judge에 실제 저장된 검증 자료를 제공한다. Stub 프로필 또는 명시적 내부 API 비활성화 시 등록하지 않는다.
 * 이 경로는 기본 제공하며 아직 선택 구현이 필요한 다른 내부 API의 구현 선택값에 의존하지 않는다.
 */
@RestController
@Profile("!internal-stub")
@ConditionalOnProperty(
    prefix = "problem.internal-api",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@RequiredArgsConstructor
public class ProblemValidationDataController {
    private final ProblemValidationDataService service;

    /**
     * 검증 요청의 고정 자료를 조회한다. API 명세서 §2.6.8을 따른다.
     *
     * @param validationId 검증 요청 ID
     * @param correlationId 호출 추적 ID
     * @return 고정 자료
     */
    @GetMapping("/internal/v1/problems/validations/{validationId}/data")
    public ValidationDataResponse read(
        @PathVariable long validationId,
        @RequestHeader("X-Correlation-Id") @NotBlank(message = "상관관계 ID는 필수입니다.") String correlationId
    ) {
        return service.read(validationId);
    }
}
