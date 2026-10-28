package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemTest {

    @Test
    @DisplayName("문제를 생성하면 공개 상태는 PRIVATE이고 전달한 작성자·용도가 그대로 저장된다")
    void createInitialState() {
        Problem problem = Problem.create(1L, ProblemScope.GENERAL);

        assertThat(problem.getAuthorId()).isEqualTo(1L);
        assertThat(problem.getScope()).isEqualTo(ProblemScope.GENERAL);
        assertThat(problem.getVisibility()).isEqualTo(ProblemVisibility.PRIVATE);
        assertThat(problem.getContestId()).isNull();
        assertThat(problem.getPublishedAt()).isNull();
    }
}
