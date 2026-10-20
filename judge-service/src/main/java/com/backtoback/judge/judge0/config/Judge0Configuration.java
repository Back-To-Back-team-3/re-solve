package com.backtoback.judge.judge0.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class Judge0Configuration {
    private final String baseUrl;
    private final String authHeader;
    private final String authToken;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    /**
     * Judge0 HTTP 통신에 필요한 설정을 받아 시간 단위를 변환한다.
     * 기본값은 application.yaml에서 관리하며 무제한 대기를 막기 위해 시간 제한 범위를 확인한다.
     *
     * @param baseUrl Judge0 서버 주소; 미설정이면 빈 문자열
     * @param authHeader 인증 헤더 이름
     * @param authToken 인증 토큰; 빈 값이면 헤더를 추가하지 않는다
     * @param connectTimeout 연결 제한 시간; 2s, 500ms 등의 형식
     * @param readTimeout 응답 제한 시간; 5s, 500ms 등의 형식
     * @throws IllegalArgumentException 시간 형식이 잘못되거나 밀리초 기준 1 이상 int 범위를 벗어난 경우
     */
    public Judge0Configuration(
        @Value("${judge0.base-url}") String baseUrl,
        @Value("${judge0.auth-header}") String authHeader,
        @Value("${judge0.auth-token}") String authToken,
        @Value("${judge0.connect-timeout}") String connectTimeout,
        @Value("${judge0.read-timeout}") String readTimeout
    ) {
        this.baseUrl = baseUrl;
        this.authHeader = authHeader;
        this.authToken = authToken;
        // @Value로 받은 시간 문자열을 한 번만 변환한다. 기본값은 Java 코드에 중복 정의하지 않는다.
        this.connectTimeout = DurationStyle.detectAndParse(connectTimeout);
        this.readTimeout = DurationStyle.detectAndParse(readTimeout);
        Assert
            .isTrue(
                this.connectTimeout.compareTo(Duration.ofMillis(1)) >= 0
                    && this.readTimeout.compareTo(Duration.ofMillis(1)) >= 0
                    && this.connectTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0
                    && this.readTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0,
                "Judge0 연결·응답 제한 시간은 1ms 이상 int 범위여야 합니다."
            );
    }

    /**
     * Judge0 전용 연결·응답 제한과 인증 헤더를 적용한 HTTP 클라이언트를 생성한다.
     * 시작 시 외부 서버에 연결하지 않으며 공통 요청 추적 설정은 유지한다.
     *
     * @param builder 공통 RestClient 설정이 적용된 빌더
     * @return 실행 생성·조회 전용 클라이언트
     */
    @Bean
    public RestClient judge0RestClient(RestClient.Builder builder) {
        HttpClient httpClient
            = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        builder = builder.clone().requestFactory(factory);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (authToken != null && !authToken.isBlank()) {
            builder.defaultHeader(authHeader, authToken);
        }
        return builder.build();
    }
}
