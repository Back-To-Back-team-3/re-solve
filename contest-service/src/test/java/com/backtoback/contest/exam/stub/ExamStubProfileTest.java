package com.backtoback.contest.exam.stub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

import com.backtoback.contest.exam.client.MemberClient;
import com.backtoback.contest.exam.client.ProblemClient;
import com.backtoback.contest.exam.client.StudyClient;
import com.backtoback.contest.exam.controller.ExamController;
import com.backtoback.contest.exam.service.ExamService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 계약용 bean이 명시적인 개발 프로필 조합에서만 등록되는지 확인한다.
 * DB·AWS 자동 설정을 로딩하지 않는 작은 컨텍스트로 프로필 조건 자체를 검증한다.
 */
class ExamStubProfileTest {
    /**
     * 두 프로필을 모두 켜면 Controller·서비스·외부 의존 대역이 함께 준비되는지 확인한다.
     */
    @Test
    @DisplayName("dev와 exam-stub을 함께 켜면 시험 API와 개발 의존 대역이 등록된다")
    void developmentProfilesRegisterApiAndStubs() {
        // given
        ApplicationContextRunner runner = contextRunner("dev", "exam-stub");

        // when & then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ExamController.class).hasSingleBean(ExamService.class);
            assertThat(context)
                .hasSingleBean(MemberClient.class)
                .hasSingleBean(StudyClient.class)
                .hasSingleBean(ProblemClient.class);
            assertThat(context.getBean(MemberClient.class).getStatus("7").status()).isEqualTo("ACTIVE");
            assertThat(context.getBean(StudyClient.class).getMembers("12").members().getFirst().role())
                .isEqualTo("LEADER");
        });
    }

    /**
     * 기본 환경·프로필 일부 누락·운영 프로필 혼합에서 가짜 성공 경로가 등록되지 않는지 확인한다.
     *
     * @param profileNames 쉼표로 구분한 활성 프로필, 빈 문자열은 기본 환경
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "dev",
            "exam-stub",
            "dev,exam-stub,prod",
            "dev,exam-stub,production"
        }
    )
    @DisplayName("개발 프로필이 부족하거나 운영 프로필이면 API와 stub이 등록되지 않는다")
    void otherProfilesDoNotRegisterApiOrStubs(String profileNames) {
        // given
        String[] profiles = profileNames.isEmpty() ? new String[0] : profileNames.split(",");
        ApplicationContextRunner runner = contextRunner(profiles);

        // when & then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ExamController.class).doesNotHaveBean(ExamService.class);
            assertThat(context)
                .doesNotHaveBean(MemberClient.class)
                .doesNotHaveBean(StudyClient.class)
                .doesNotHaveBean(ProblemClient.class);
        });
    }

    /**
     * bean 등록과 프로필 판단만 확인할 작은 컨텍스트를 준비한다. HTTP·DB·외부 호출은 하지 않는다.
     *
     * @param profiles 적용할 프로필 목록
     * @return 실행 전 컨텍스트 준비 객체
     */
    private ApplicationContextRunner contextRunner(String... profiles) {
        return new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
            .withBean(HttpServletRequest.class, MockHttpServletRequest::new)
            .withUserConfiguration(
                ExamStubConfiguration.class,
                ExamStubScenario.class,
                StubExamService.class,
                ExamController.class
            );
    }
}
