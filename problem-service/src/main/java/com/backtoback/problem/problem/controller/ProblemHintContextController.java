package com.backtoback.problem.problem.controller;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemServiceCondition;
import com.backtoback.problem.problem.dto.response.ProblemHintContextResponse;
import com.backtoback.problem.problem.service.ProblemHintContextService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** Study에 문제 단위 힌트 입력을 제공하며 회원별 차단은 판단하지 않는다. (API §2.6.6) */
@RestController
@Profile("!internal-stub")
@Conditional(InternalProblemServiceCondition.class)
@RequiredArgsConstructor
public class ProblemHintContextController {
    private final ProblemHintContextService service;

    /**
     * 공개 사용 회차의 힌트 문맥을 조회한다. (API 명세서 §2.6.6 힌트 문맥 조회)
     *
     * @param problemId 양수 BIGINT 문제 식별자
     * @return 공통 래퍼 없는 Study 입력
     * @throws com.backtoback.problem.global.exception.ProblemException 문제 없음 또는 의존 조회 장애인 경우
     */
    @GetMapping("/internal/v1/problems/{problemId}/hint-context")
    public ProblemHintContextResponse read(@PathVariable @Positive(message = "문제 ID는 양수여야 합니다.") long problemId) {
        return ProblemHintContextResponse.from(service.read(problemId));
    }
}
