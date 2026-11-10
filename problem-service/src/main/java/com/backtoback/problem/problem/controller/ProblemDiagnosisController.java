package com.backtoback.problem.problem.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.dto.response.DiagnosisCandidatesResponse;
import com.backtoback.problem.problem.service.ProblemReadStore;

import lombok.RequiredArgsConstructor;

/** Member가 진단에 출제할 수 있는 문제의 고정 회차를 제공한다. */
@RestController
@Profile("!internal-stub")
@ConditionalOnProperty(
    prefix = "problem.internal-api",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@RequiredArgsConstructor
public class ProblemDiagnosisController {
    private final ProblemReadStore store;

    /**
     * 태그별 진단 후보를 난이도 순서로 반환한다(API 명세서 §2.6.7).
     *
     * @param tagId 진단 태그 ID
     * @return 난이도 1~3의 출제 후보
     */
    @GetMapping("/internal/v1/problems/diagnosis-candidates")
    public DiagnosisCandidatesResponse candidates(@RequestParam long tagId) {
        return DiagnosisCandidatesResponse.from(tagId, store.diagnosisCandidates(tagId));
    }
}
