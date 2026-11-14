package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 로컬·운영 프로필의 LocalStack 설정 경계를 확인한다. */
class LocalStackConfigurationTest {

    private final ApplicationContextRunner contextRunner
        = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("로컬 프로필에서 환경변수 재정의와 관계없이 고정 테스트 자격 증명을 사용한다")
    void localProfileUsesFixedTestCredentialsDespiteEnvironmentOverrides() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                "AWS_ACCESS_KEY_ID=overridden-access-key",
                "AWS_SECRET_ACCESS_KEY=overridden-secret-key"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                var environment = context.getEnvironment();
                assertThat(environment.getProperty("spring.cloud.aws.endpoint")).isEqualTo("http://localhost:4566");
                assertThat(environment.getProperty("spring.cloud.aws.credentials.access-key")).isEqualTo("test");
                assertThat(environment.getProperty("spring.cloud.aws.credentials.secret-key")).isEqualTo("test");
            });
    }

    @Test
    @DisplayName("로컬 프로필에서 설정된 LocalStack 주소를 사용한다")
    void localProfileUsesConfiguredLocalStackEndpoint() {
        contextRunner
            .withPropertyValues("spring.profiles.active=local", "LOCALSTACK_ENDPOINT=http://localstack:4566")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("spring.cloud.aws.endpoint"))
                    .isEqualTo("http://localstack:4566");
            });
    }

    @Test
    @DisplayName("운영 프로필에는 LocalStack 주소와 로컬 테스트 자격 증명을 적용하지 않는다")
    void prodProfileDoesNotApplyLocalStackSettings() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod", "LOCALSTACK_ENDPOINT=http://localstack:4566")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var environment = context.getEnvironment();
                assertThat(environment.getProperty("spring.cloud.aws.endpoint")).isNull();
                assertThat(environment.getProperty("spring.cloud.aws.credentials.access-key")).isNull();
                assertThat(environment.getProperty("spring.cloud.aws.credentials.secret-key")).isNull();
                assertThat(environment.getProperty("spring.cloud.aws.s3.path-style-access-enabled")).isNull();
            });
    }
}
