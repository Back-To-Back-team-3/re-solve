package com.backtoback.judge.judging.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * Judge0 설정 바인딩·검증과 HTTP 클라이언트 정책을 검증한다.
 */
class Judge0ConfigurationTest {
    private final ApplicationContextRunner runner
        = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Judge0Configuration.class, HttpConfiguration.class);

    /**
     * application.yaml의 기본값이 생성자에 주입되고 외부 호출 없이 HTTP 클라이언트를 만드는지 검증한다.
     */
    @Test
    @DisplayName("YAML 기본값을 record에 바인딩하고 외부 호출 없이 클라이언트를 생성한다")
    void injectsYamlDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
            var properties = context.getBean(Judge0Properties.class);
            assertThat(properties.baseUrl()).isEqualTo("");
            assertThat(properties.authHeader()).isEqualTo("X-Auth-Token");
            assertThat(properties.authToken()).isEqualTo("");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.languages().java().enabled()).isFalse();
            assertThat(properties.languages().python3().enabled()).isFalse();
            assertThat(properties.languages().cpp().enabled()).isFalse();

        });
    }

    /**
     * 환경변수로 덮어쓴 주소·인증·시간 제한 설정을 생성자에서 받는지 검증한다.
     */
    @Test
    @DisplayName("환경변수 설정을 record에 바인딩하고 시간 단위를 Duration으로 변환한다")
    void injectsEnvironmentOverrides() {
        runner
            .withPropertyValues(
                "JUDGE0_BASE_URL=http://localhost:2358",
                "JUDGE0_AUTH_HEADER=X-Judge-Token",
                "JUDGE0_AUTH_TOKEN=test-token",
                "JUDGE0_CONNECT_TIMEOUT=1500ms",
                "JUDGE0_READ_TIMEOUT=3s",
                "JUDGE0_JAVA_ENABLED=true",
                "JUDGE0_PYTHON3_ENABLED=false",
                "JUDGE0_CPP_ENABLED=true"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(Judge0Properties.class);
                assertThat(properties.baseUrl()).isEqualTo("http://localhost:2358");
                assertThat(properties.authHeader()).isEqualTo("X-Judge-Token");
                assertThat(properties.authToken()).isEqualTo("test-token");
                assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
                assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(properties.languages().java().enabled()).isTrue();
                assertThat(properties.languages().python3().enabled()).isFalse();
                assertThat(properties.languages().cpp().enabled()).isTrue();

            });
    }

    /**
     * 0·음수·범위 초과·해석 불가능한 시간 제한은 HTTP 클라이언트 생성 전에 거부하는지 검증한다.
     *
     * @param property 잘못된 시간 제한 설정
     */
    @ParameterizedTest
    @DisplayName("잘못된 시간 제한은 설정 바인딩 단계에서 기동을 거부한다")
    @ValueSource(
        strings = {
            "judge0.connect-timeout=0ms",
            "judge0.read-timeout=0ms",
            "judge0.read-timeout=-1s",
            "judge0.connect-timeout=2147483648ms",
            "judge0.read-timeout=invalid",
            "judge0.connect-timeout=-1ms",
            "judge0.connect-timeout=0.5ms",
            "judge0.read-timeout=2147483648ms",
            "judge0.read-timeout="
        }
    )
    void rejectsInvalidTimeout(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    /**
     * 연결·응답 제한에 허용한 최솟값과 최댓값으로 클라이언트를 생성하는지 검증한다.
     *
     * @param timeout 허용 범위의 시간 제한
     */
    @ParameterizedTest
    @DisplayName("1ms와 int 밀리초 최댓값을 연결·응답 제한으로 허용한다")
    @ValueSource(
        strings = {
            "1ms",
            "2147483647ms"
        }
    )
    void acceptsTimeoutBoundaries(String timeout) {
        runner
            .withPropertyValues("judge0.connect-timeout=" + timeout, "judge0.read-timeout=" + timeout)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
                var properties = context.getBean(Judge0Properties.class);
                assertThat(properties.connectTimeout()).isEqualTo(properties.readTimeout());
            });
    }

    /**
     * 설정으로 생성한 클라이언트의 인증·리다이렉트 정책과 공통 요청 추적을 실제 HTTP 요청으로 검증한다.
     *
     * @param token 설정할 인증 토큰; 빈 값이면 인증 헤더를 보내지 않는다
     * @throws IOException 로컬 HTTP 서버를 시작하지 못한 경우
     */
    @ParameterizedTest
    @DisplayName("선택적 인증과 요청 추적을 적용하고 HTTP 리다이렉트를 따르지 않는다")
    @ValueSource(
        strings = {
            "",
            "test-token"
        }
    )
    void appliesClientSettingsWithoutFollowingRedirects(String token) throws IOException {
        var receivedToken = new AtomicReference<String>();
        var correlationId = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Judge-Token"));
            correlationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            exchange.getResponseHeaders().add("Location", "/followed");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/followed", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            runner
                .withPropertyValues(
                    "judge0.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "judge0.auth-header=X-Judge-Token",
                    "judge0.auth-token=" + token
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(RestClient.class);
                    assertThat(client.get().uri("/probe").retrieve().toBodilessEntity().getStatusCode())
                        .isEqualTo(HttpStatus.FOUND);
                    assertThat(receivedToken.get()).isEqualTo(token.isEmpty() ? null : token);
                    assertThat(correlationId.get()).isEqualTo("trace-88");
                });
        } finally {
            server.stop(0);
        }
    }

    /**
     * 로컬 주소를 적용해도 공통 인증·시간 제한 설정이 유지되는지 검증한다.
     */
    @Test
    @DisplayName("로컬 프로필은 호스트 Judge0 주소와 공통 인증·시간 제한을 함께 적용한다")
    void localProfilePreservesSharedCommunicationSettings() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
            var properties = context.getBean(Judge0Properties.class);
            assertThat(properties.baseUrl()).isEqualTo("http://localhost:2358");
            assertThat(properties.authHeader()).isEqualTo("X-Auth-Token");
            assertThat(properties.authToken()).isEmpty();
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    /**
     * Compose에서 전달한 주소·인증·시간 제한이 로컬 프로필 기본값보다 우선하는지 검증한다.
     */
    @Test
    @DisplayName("로컬 프로필에서도 Compose가 전달한 Judge0 통신 설정을 우선 적용한다")
    void localProfileUsesComposeCommunicationSettings() {
        runner
            .withPropertyValues(
                "spring.profiles.active=local",
                "JUDGE0_BASE_URL=http://judge0-server:2358",
                "JUDGE0_AUTH_HEADER=X-Judge-Token",
                "JUDGE0_AUTH_TOKEN=test-token",
                "JUDGE0_CONNECT_TIMEOUT=1500ms",
                "JUDGE0_READ_TIMEOUT=3s"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
                var properties = context.getBean(Judge0Properties.class);
                assertThat(properties.baseUrl()).isEqualTo("http://judge0-server:2358");
                assertThat(properties.authHeader()).isEqualTo("X-Judge-Token");
                assertThat(properties.authToken()).isEqualTo("test-token");
                assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
                assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            });
    }

    /**
     * 요청 추적 적용 여부를 검증할 HTTP 클라이언트 빌더를 등록한다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class HttpConfiguration {
        /**
         * 외부 서버 없이 설정 주입을 검증할 HTTP 클라이언트 빌더를 제공한다.
         *
         * @return 테스트용 빌더
         */
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder().requestInterceptor((request, body, execution) -> {
                request.getHeaders().set("X-Correlation-Id", "trace-88");
                return execution.execute(request, body);
            });
        }
    }
}
