package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalStackConfigurationTests {

    private final ApplicationContextRunner contextRunner
        = new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    /**
     * 로컬 프로필에서 PC용 LocalStack 기본 주소와 환경변수에 영향받지 않는 고정 테스트 자격 증명을 사용하는지 검증한다.
     */
    @Test
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

    /**
     * 로컬 프로필에서 Compose가 전달한 LocalStack 전용 주소를 사용하는지 검증한다.
     */
    @Test
    void localProfileUsesConfiguredLocalStackEndpoint() {
        contextRunner
            .withPropertyValues("spring.profiles.active=local", "LOCALSTACK_ENDPOINT=http://localstack:4566")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("spring.cloud.aws.endpoint"))
                    .isEqualTo("http://localstack:4566");
            });
    }

    /**
     * 운영 프로필에서 LocalStack 전용 주소와 로컬 테스트 자격 증명이 AWS 설정에 적용되지 않는지 검증한다.
     */
    @Test
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
