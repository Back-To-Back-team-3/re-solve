package com.backtoback.problem.problem.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Judge 주소 설정의 애플리케이션 시작 검증을 확인한다. */
class ProblemIntegrationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    /** 기본 주소의 설정 바인딩과 검증이 성공한다. */
    @Test
    @DisplayName("Judge 기본 주소는 시작 시 검증을 통과한다")
    void defaultAddress() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProblemIntegrationProperties.class).judgeUrl().toString())
                .isEqualTo("http://judge-service:8080");
        });
    }

    /**
     * HTTP(S)가 아니거나 호스트가 없는 주소를 시작 단계에서 거절한다.
     *
     * @param address 잘못된 주소
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/judge",
            "judge-service:8080",
            "http:/judge",
            "file:///judge",
            "ftp://judge"
        }
    )
    @DisplayName("상대 주소와 호스트가 없는 주소 및 HTTP(S) 이외 주소는 시작에 실패한다")
    void rejectsInvalidAddress(String address) {
        runner
            .withPropertyValues("problem.integration.judge-url=" + address)
            .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 명시한 HTTP(S) 주소가 설정 검증을 통과한다.
     *
     * @param address 유효한 주소
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://localhost:8080",
            "https://judge.example.com"
        }
    )
    @DisplayName("호스트가 있는 HTTP(S) 주소는 시작 시 검증을 통과한다")
    void acceptsAbsoluteAddress(String address) {
        runner
            .withPropertyValues("problem.integration.judge-url=" + address)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProblemIntegrationProperties.class)
    static class Config {
        // 설정 바인딩과 시작 시 검증만 활성화한다.
    }
}
