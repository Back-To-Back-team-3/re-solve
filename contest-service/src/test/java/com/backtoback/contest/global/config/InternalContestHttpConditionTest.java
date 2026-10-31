package com.backtoback.contest.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import com.backtoback.contest.exam.controller.InternalContestController;
import com.backtoback.contest.exam.service.InternalContestService;
import com.backtoback.contest.exam.service.InternalContestStubService;

/**
 * 명시적 프로필·구현 선택에 따라 Controller와 Stub이 함께 등록되는지 검증한다.
 * 실제 구현 선택 시에는 같은 Service 인터페이스만 주입한다.
 */
class InternalContestHttpConditionTest {
    private final ApplicationContextRunner runner
        = new ApplicationContextRunner()
            .withUserConfiguration(InternalContestController.class, InternalContestStubService.class);

    @Test
    @DisplayName("local·internal-stub 프로필은 선택 YAML을 읽고 Controller와 Stub을 함께 등록한다")
    void activatesSelectedStub() {
        // given
        var configured = configRunner("local,internal-stub");

        // when & then
        configured.run(context -> {
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(InternalContestController.class)
                .hasSingleBean(InternalContestService.class)
                .hasSingleBean(InternalContestStubService.class);
            assertThat(context.getEnvironment().getProperty("contest.internal-http.mode")).isEqualTo("stub");
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).startsWith("jdbc:mysql:");
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("spring.cloud.aws.sns.enabled", Boolean.class)).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource(
        {
            "default",
            "local",
            "internal-stub",
            "prod",
            "staging"
        }
    )
    @DisplayName("Stub 실행 조합을 선택하지 않으면 Controller와 Stub을 모두 등록하지 않는다")
    void doesNotActivateUnselectedProfiles(String profiles) {
        // given
        var configured = configRunner(profiles);

        // when & then
        configured
            .run(
                context -> assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(InternalContestController.class)
                    .doesNotHaveBean(InternalContestService.class)
            );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "default",
            "local",
            "internal-stub",
            "prod",
            "staging"
        }
    )
    @DisplayName("구현 값만 stub으로 지정해도 필수 개발 프로필이 없으면 등록하지 않는다")
    void requiresBothProfiles(String profiles) {
        // given
        var configured
            = runner.withPropertyValues("spring.profiles.active=" + profiles, "contest.internal-http.mode=stub");

        // when & then
        configured
            .run(
                context -> assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(InternalContestController.class)
                    .doesNotHaveBean(InternalContestService.class)
            );
    }

    @ParameterizedTest
    @CsvSource(
        {
            "disabled",
            "unknown"
        }
    )
    @DisplayName("개발 프로필에서도 Stub 선택을 해제하면 Controller만 남지 않는다")
    void disablesSelectedStub(String mode) {
        // given
        var configured = configRunner("local,internal-stub").withPropertyValues("contest.internal-http.mode=" + mode);

        // when & then
        configured
            .run(
                context -> assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(InternalContestController.class)
                    .doesNotHaveBean(InternalContestService.class)
            );
    }

    @Test
    @DisplayName("같은 개발 프로필에서 real을 선택하면 실제 Service만 주입하고 Stub은 제외한다")
    void selectsRealImplementation() {
        // given
        var realService = mock(InternalContestService.class);
        var configured
            = configRunner("local,internal-stub")
                .withPropertyValues("contest.internal-http.mode=real")
                .withBean("realInternalContestService", InternalContestService.class, () -> realService);

        // when & then
        configured.run(context -> {
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(InternalContestController.class)
                .hasSingleBean(InternalContestService.class)
                .doesNotHaveBean(InternalContestStubService.class);
            assertThat(context.getBean(InternalContestService.class)).isSameAs(realService);
        });
    }

    @Test
    @DisplayName("실제 프로필의 real 선택도 Stub 없이 Controller에 구현을 연결한다")
    void selectsRealWithoutStubProfile() {
        // given
        var configured
            = runner
                .withPropertyValues("contest.internal-http.mode=real")
                .withBean(InternalContestService.class, () -> mock(InternalContestService.class));

        // when & then
        configured
            .run(
                context -> assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(InternalContestController.class)
                    .hasSingleBean(InternalContestService.class)
                    .doesNotHaveBean(InternalContestStubService.class)
            );
    }

    private ApplicationContextRunner configRunner(String profiles) {
        return runner.withInitializer(context -> {
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource(
                        "stub-config",
                        Map.of("spring.config.location", "classpath:/", "spring.profiles.active", profiles)
                    )
                );
            new ConfigDataApplicationContextInitializer().initialize(context);
        });
    }
}
