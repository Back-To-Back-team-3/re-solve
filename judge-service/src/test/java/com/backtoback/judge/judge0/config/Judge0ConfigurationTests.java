package com.backtoback.judge.judge0.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class Judge0ConfigurationTests {
    private final ApplicationContextRunner runner
        = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Judge0Configuration.class, HttpConfiguration.class);

    /**
     * application.yaml의 기본값이 생성자에 주입되고 외부 호출 없이 HTTP 클라이언트를 만드는지 검증한다.
     */
    @Test
    void injectsYamlDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
            var configuration = context.getBean(Judge0Configuration.class);
            assertThat(ReflectionTestUtils.getField(configuration, "baseUrl")).isEqualTo("");
            assertThat(ReflectionTestUtils.getField(configuration, "authHeader")).isEqualTo("X-Auth-Token");
            assertThat(ReflectionTestUtils.getField(configuration, "authToken")).isEqualTo("");
            assertThat(ReflectionTestUtils.getField(configuration, "connectTimeout")).isEqualTo(Duration.ofSeconds(2));
            assertThat(ReflectionTestUtils.getField(configuration, "readTimeout")).isEqualTo(Duration.ofSeconds(5));

        });
    }

    /**
     * 환경변수로 덮어쓴 주소·인증·시간 제한 설정을 생성자에서 받는지 검증한다.
     */
    @Test
    void injectsEnvironmentOverrides() {
        runner
            .withPropertyValues(
                "JUDGE0_BASE_URL=http://localhost:2358",
                "JUDGE0_AUTH_HEADER=X-Judge-Token",
                "JUDGE0_AUTH_TOKEN=test-token",
                "JUDGE0_CONNECT_TIMEOUT=1500ms",
                "JUDGE0_READ_TIMEOUT=3s"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                var configuration = context.getBean(Judge0Configuration.class);
                assertThat(ReflectionTestUtils.getField(configuration, "baseUrl")).isEqualTo("http://localhost:2358");
                assertThat(ReflectionTestUtils.getField(configuration, "authHeader")).isEqualTo("X-Judge-Token");
                assertThat(ReflectionTestUtils.getField(configuration, "authToken")).isEqualTo("test-token");
                assertThat(ReflectionTestUtils.getField(configuration, "connectTimeout"))
                    .isEqualTo(Duration.ofMillis(1500));
                assertThat(ReflectionTestUtils.getField(configuration, "readTimeout")).isEqualTo(Duration.ofSeconds(3));

            });
    }

    /**
     * 0·음수·범위 초과·해석 불가능한 시간 제한은 HTTP 클라이언트 생성 전에 거부하는지 검증한다.
     *
     * @param property 잘못된 시간 제한 설정
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "judge0.connect-timeout=0ms",
            "judge0.read-timeout=0ms",
            "judge0.read-timeout=-1s",
            "judge0.connect-timeout=2147483648ms",
            "judge0.read-timeout=invalid"
        }
    )
    void rejectsInvalidTimeout(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HttpConfiguration {
        /**
         * 외부 서버 없이 설정 주입을 검증할 HTTP 클라이언트 빌더를 제공한다.
         *
         * @return 테스트용 빌더
         */
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
