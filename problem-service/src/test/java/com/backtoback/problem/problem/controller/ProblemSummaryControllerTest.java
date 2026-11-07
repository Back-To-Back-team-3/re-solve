package com.backtoback.problem.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.backtoback.problem.problem.service.ProblemReadStore;

/** 실제 내부 요약 경로의 명시적 활성화 조건을 검증한다. */
class ProblemSummaryControllerTest {
    /** 설정 누락·비활성화·다른 구현·Stub 프로필에서는 실제 요약 컨트롤러를 제외한다. */
    @Test
    @DisplayName("내부 요약은 enabled와 real 구현을 명시한 일반 프로필에서만 등록한다")
    void activationConditions() {
        var runner
            = new ApplicationContextRunner()
                .withBean(ProblemReadStore.class, () -> mock(ProblemReadStore.class))
                .withUserConfiguration(ProblemSummaryController.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(ProblemSummaryController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ProblemSummaryController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=false", "problem.internal-api.implementation=real")
            .run(context -> assertThat(context).doesNotHaveBean(ProblemSummaryController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=true", "problem.internal-api.implementation=stub")
            .run(context -> assertThat(context).doesNotHaveBean(ProblemSummaryController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=true", "problem.internal-api.implementation=real")
            .run(context -> assertThat(context).hasSingleBean(ProblemSummaryController.class));
        runner
            .withPropertyValues(
                "spring.profiles.active=internal-stub",
                "problem.internal-api.enabled=true",
                "problem.internal-api.implementation=stub",
                "problem.internal-stub.enabled=true"
            )
            .run(context -> assertThat(context).doesNotHaveBean(ProblemSummaryController.class));
    }
}
