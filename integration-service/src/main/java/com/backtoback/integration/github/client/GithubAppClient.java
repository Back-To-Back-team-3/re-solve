package com.backtoback.integration.github.client;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.backtoback.integration.github.client.dto.GithubInstallationResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationTokenResponse;
import com.backtoback.integration.github.client.dto.GithubRepositoriesResponse;
import com.backtoback.integration.github.config.GithubProperties;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

import lombok.extern.slf4j.Slf4j;

/**
 * GitHub App REST API 클라이언트 (api-spec.md > 8.1.2, 8.1.3).
 * GitHub App 인증(RS256 JWT)을 통해 설치 정보 및 저장소를 조회하고 Access Token을 발급받는다.
 */
@Slf4j
@Component
public class GithubAppClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final String GITHUB_MEDIA_TYPE = "application/vnd.github+json";

    private final GithubProperties properties;
    private final RestClient restClient;

    @Autowired
    public GithubAppClient(GithubProperties properties) {
        this(properties, RestClient.builder().requestFactory(timeoutRequestFactory()).build());
    }

    GithubAppClient(GithubProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * GitHub App 설치 URL을 생성한다 (api-spec.md > 8.1.2).
     *
     * @param state CSRF 검증용 state 값
     * @return 설치 URL 문자열
     */
    public String buildInstallUrl(String state) {
        return UriComponentsBuilder
            .fromUriString("https://github.com/apps/" + properties.getAppSlug() + "/installations/new")
            .queryParam("state", state)
            .build()
            .toUriString();
    }

    /**
     * GitHub App 설치 정보를 조회한다 (GET /app/installations/{installation_id}).
     *
     * @param installationId GitHub App 설치 ID
     * @return 설치 정보
     */
    public GithubInstallationResponse getInstallation(Long installationId) {
        String jwt = createAppJwt();
        try {
            return restClient
                .get()
                .uri(properties.getApiBaseUrl() + "/app/installations/" + installationId)
                .header("Authorization", "Bearer " + jwt)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .accept(MediaType.parseMediaType(GITHUB_MEDIA_TYPE))
                .retrieve()
                .body(GithubInstallationResponse.class);
        } catch (RestClientException e) {
            log.error("GitHub App 설치 정보 조회 실패: installationId={}, error={}", installationId, e.getMessage());
            throw new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, e);
        }
    }

    /**
     * GitHub App 설치에 대한 Access Token을 발급받는다 (POST /app/installations/{installation_id}/access_tokens).
     *
     * @param installationId GitHub App 설치 ID
     * @return 발급된 설치 토큰 정보
     */
    public GithubInstallationTokenResponse createInstallationAccessToken(Long installationId) {
        String jwt = createAppJwt();
        try {
            return restClient
                .post()
                .uri(properties.getApiBaseUrl() + "/app/installations/" + installationId + "/access_tokens")
                .header("Authorization", "Bearer " + jwt)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .accept(MediaType.parseMediaType(GITHUB_MEDIA_TYPE))
                .retrieve()
                .body(GithubInstallationTokenResponse.class);
        } catch (RestClientException e) {
            log.error("GitHub App Access Token 발급 실패: installationId={}, error={}", installationId, e.getMessage());
            throw new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, e);
        }
    }

    /**
     * 설치된 저장소 목록을 조회한다 (GET /installation/repositories).
     *
     * @param installationToken 설치 Access Token
     * @return 저장소 목록 응답
     */
    public GithubRepositoriesResponse getInstallationRepositories(String installationToken) {
        try {
            return restClient
                .get()
                .uri(properties.getApiBaseUrl() + "/installation/repositories")
                .header("Authorization", "Bearer " + installationToken)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .accept(MediaType.parseMediaType(GITHUB_MEDIA_TYPE))
                .retrieve()
                .body(GithubRepositoriesResponse.class);
        } catch (RestClientException e) {
            log.error("GitHub App 저장소 목록 조회 실패: error={}", e.getMessage());
            throw new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, e);
        }
    }

    /**
     * GitHub App Private Key를 사용하여 RS256 JWT를 생성한다.
     * 유효기간은 최대 10분(GitHub 제한), iat는 시계 오차 대비 60초 전으로 설정.
     *
     * @return 서명된 JWT 문자열
     */
    public String createAppJwt() {
        if (properties.getPrivateKey() == null || properties.getPrivateKey().isBlank()) {
            throw new IllegalStateException("GitHub App Private Key가 설정되지 않았습니다.");
        }
        if (properties.getAppId() == null || properties.getAppId().isBlank()) {
            throw new IllegalStateException("GitHub App ID가 설정되지 않았습니다.");
        }

        try {
            PrivateKey privateKey = parsePrivateKey(properties.getPrivateKey());
            long now = Instant.now().getEpochSecond();
            long iat = now - 60;
            long exp = now + (9 * 60); // 9분 후 만료

            String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String payloadJson
                = String.format("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}", iat, exp, properties.getAppId());

            Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
            String headerEncoded = urlEncoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadEncoded = urlEncoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = headerEncoded + "." + payloadEncoded;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = signature.sign();
            String signatureEncoded = urlEncoder.encodeToString(signatureBytes);

            return signingInput + "." + signatureEncoded;
        } catch (Exception e) {
            throw new IllegalStateException("GitHub App JWT 생성 실패", e);
        }
    }

    private PrivateKey parsePrivateKey(String key) throws Exception {
        String cleanKey
            = key
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }
}
