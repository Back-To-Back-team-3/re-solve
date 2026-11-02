package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

class ProblemTest {

    @Test
    @DisplayName("문제를 생성하면 공개 상태는 PRIVATE, 버전은 1이며 전달한 작성자·용도가 그대로 저장된다")
    void createInitialState() {
        Problem problem = Problem.create(1L, ProblemScope.GENERAL);

        assertThat(problem.getAuthorId()).isEqualTo(1L);
        assertThat(problem.getScope()).isEqualTo(ProblemScope.GENERAL);
        assertThat(problem.getVisibility()).isEqualTo(ProblemVisibility.PRIVATE);
        assertThat(problem.getContestId()).isNull();
        assertThat(problem.getContestProblemRevisionId()).isNull();
        assertThat(problem.getContestLinkSourceVersion()).isNull();
        assertThat(problem.getContestPolicyActorId()).isNull();
        assertThat(problem.getRowVersion()).isEqualTo(1);
        assertThat(problem.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("작성자가 현재 버전으로 새 회차를 준비하면 문제 버전이 증가한다")
    void increasesVersionForNewRevision() {
        Problem problem = Problem.create(1L, ProblemScope.GENERAL);
        problem.prepareNewRevision(1L, 1);
        assertThat(problem.getRowVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("작성자가 다르거나 버전이 오래되면 문제 버전을 변경하지 않는다")
    void rejectsWrongAuthorAndVersion() {
        Problem problem = Problem.create(1L, ProblemScope.GENERAL);
        assertThatThrownBy(() -> problem.prepareNewRevision(2L, 1))
            .isInstanceOfSatisfying(
                ProblemException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
        assertThatThrownBy(() -> problem.prepareNewRevision(1L, 0))
            .isInstanceOfSatisfying(
                ProblemException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_VERSION_CONFLICT)
            );
        assertThat(problem.getRowVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("보관된 문제는 새 회차를 준비할 수 없다")
    void rejectsArchivedProblem() {
        Problem problem = Problem.create(1L, ProblemScope.GENERAL);
        ReflectionTestUtils.setField(problem, "visibility", ProblemVisibility.ARCHIVED);
        assertThatThrownBy(() -> problem.prepareNewRevision(1L, 1))
            .isInstanceOfSatisfying(
                ProblemException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION)
            );
        assertThat(problem.getRowVersion()).isEqualTo(1);
    }
}
