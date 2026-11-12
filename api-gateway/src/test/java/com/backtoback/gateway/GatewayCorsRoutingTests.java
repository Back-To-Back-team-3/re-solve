package com.backtoback.gateway;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/** Gateway의 CORS 사전 요청과 공개·인증 경로 라우팅을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayCorsRoutingTests {

    private static final String JWT_SECRET = "gateway-cors-test-secret-key-must-be-at-least-32-bytes";

    private static HttpServer problemServer;
    private static HttpServer judgeServer;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        given(redisTemplate.hasKey(anyString())).willReturn(Mono.just(false));
    }

    @DynamicPropertySource
    static void registerDownstreamUrls(DynamicPropertyRegistry registry) throws IOException {
        problemServer = startDownstreamServer(exchange -> "problem-service");
        judgeServer = startDownstreamServer(exchange -> {
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            String role = exchange.getRequestHeaders().getFirst("X-User-Role");
            return "judge-service:" + userId + ":" + role;
        });

        registry.add("PROBLEM_SERVICE_URL", () -> getBaseUrl(problemServer));
        registry.add("JUDGE_SERVICE_URL", () -> getBaseUrl(judgeServer));
        registry.add("JWT_SECRET", () -> JWT_SECRET);
    }

    @AfterAll
    static void stopDownstreamServers() {
        if (problemServer != null) {
            problemServer.stop(0);
        }
        if (judgeServer != null) {
            judgeServer.stop(0);
        }
    }

    @Test
    @DisplayName("허용된 출처의 OPTIONS 사전 요청은 CORS 응답 헤더를 반환한다")
    void preflightRequestReturnsCorsHeaders() {
        // given
        var request
            = webTestClient
                .options()
                .uri("/api/v1/problems")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization");

        // when
        var response = request.exchange();

        // then
        response
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
            .expectHeader()
            .valueEquals("Access-Control-Allow-Credentials", "true")
            .expectHeader()
            .valueEquals("Access-Control-Allow-Methods", "GET")
            .expectHeader()
            .valueEquals("Access-Control-Allow-Headers", "Authorization")
            .expectHeader()
            .valueEquals("Access-Control-Max-Age", "3600")
            .expectBody()
            .isEmpty();
    }

    @Test
    @DisplayName("공개 문제 조회 요청은 problem-service로 라우팅한다")
    void publicProblemRequestRoutesToProblemService() {
        // given
        var request = webTestClient.get().uri("/api/v1/problems").header("Origin", "http://localhost:3000");

        // when
        var response = request.exchange();

        // then
        response
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
            .expectBody(String.class)
            .isEqualTo("problem-service");
    }

    @Test
    @DisplayName("유효한 인증 토큰의 제출 조회는 사용자 식별 정보를 judge-service로 전달한다")
    void authenticatedSubmissionRequestRoutesWithUserIdentity() throws Exception {
        // given
        String token = createToken();
        var request
            = webTestClient
                .get()
                .uri("/api/v1/submissions")
                .header("Origin", "http://localhost:3000")
                .header("Authorization", "Bearer " + token);

        // when
        var response = request.exchange();

        // then
        response
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
            .expectBody(String.class)
            .isEqualTo("judge-service:7:USER");
    }

    /** 응답 본문을 지정하는 로컬 다운스트림 서버를 시작한다. */
    private static HttpServer startDownstreamServer(Function<HttpExchange, String> responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responseBody.apply(exchange).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }

    /** 다운스트림 서버 주소를 Gateway 라우트 URL로 변환한다. */
    private static String getBaseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Gateway 인증 필터가 검증할 HS256 테스트 토큰을 만든다. */
    private String createToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims
            = new JWTClaimsSet.Builder()
                .issuer("re-solve")
                .subject("7")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString())
                .claim("role", "USER")
                .build();

        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }
}
