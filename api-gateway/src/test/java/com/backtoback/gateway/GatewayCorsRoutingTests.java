package com.backtoback.gateway;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCorsRoutingTests {

    private static final String JWT_SECRET = "gateway-cors-test-secret-key-must-be-at-least-32-bytes";

    private static HttpServer downstreamServer;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        given(redisTemplate.hasKey(anyString())).willReturn(Mono.just(false));
    }

    @DynamicPropertySource
    static void registerDownstreamUrl(DynamicPropertyRegistry registry) throws IOException {
        downstreamServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        downstreamServer.createContext("/", exchange -> {
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            String role = exchange.getRequestHeaders().getFirst("X-User-Role");
            String body = userId == null ? "routed" : userId + ":" + role;
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
        });
        downstreamServer.start();

        String downstreamUrl = "http://localhost:" + downstreamServer.getAddress().getPort();
        registry.add("PROBLEM_SERVICE_URL", () -> downstreamUrl);
        registry.add("JUDGE_SERVICE_URL", () -> downstreamUrl);
        registry.add("JWT_SECRET", () -> JWT_SECRET);
    }

    @AfterAll
    static void stopDownstreamServer() {
        if (downstreamServer != null) {
            downstreamServer.stop(0);
        }
    }

    @Test
    @DisplayName("허용된 출처의 OPTIONS 사전 요청은 CORS 응답 헤더를 반환한다")
    void preflightRequestReturnsCorsHeaders() {
        webTestClient
            .options()
            .uri("/api/v1/problems")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "Authorization")
            .exchange()
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
            .valueEquals("Access-Control-Max-Age", "3600");
    }

    @Test
    @DisplayName("공개 문제 조회 요청은 problem-service로 라우팅한다")
    void publicProblemRequestRoutesToProblemService() {
        webTestClient
            .get()
            .uri("/api/v1/problems")
            .header("Origin", "http://localhost:3000")
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
            .expectBody(String.class)
            .isEqualTo("routed");
    }

    @Test
    @DisplayName("유효한 인증 토큰의 제출 조회 요청은 사용자 식별 정보를 전달해 라우팅한다")
    void authenticatedSubmissionRequestRoutesWithUserIdentity() throws Exception {
        webTestClient
            .get()
            .uri("/api/v1/submissions")
            .header("Origin", "http://localhost:3000")
            .header("Authorization", "Bearer " + createToken())
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
            .expectBody(String.class)
            .isEqualTo("7:USER");
    }

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
