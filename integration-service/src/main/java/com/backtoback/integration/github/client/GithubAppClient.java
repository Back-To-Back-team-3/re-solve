package com.backtoback.integration.github.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.integration.github.client.dto.GithubInstallationResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationTokenResponse;
import com.backtoback.integration.github.client.dto.GithubRepositoriesResponse;
import com.backtoback.integration.github.config.GithubProperties;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

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

    // RSA Encryption AlgorithmIdentifier DER (1.2.840.113549.1.1.1): SEQUENCE { OID, NULL }
    private static final byte[] RSA_ALGORITHM_IDENTIFIER
        = new byte[] {
            0x30,
            0x0d,
            0x06,
            0x09,
            0x2a,
            (byte)0x86,
            0x48,
            (byte)0x86,
            (byte)0xf7,
            0x0d,
            0x01,
            0x01,
            0x01,
            0x05,
            0x00
        };
    private static final byte[] PKCS8_VERSION
        = new byte[] {
            0x02,
            0x01,
            0x00
        };

    private final GithubProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GithubAppClient(GithubProperties properties, ObjectMapper objectMapper) {
        this(properties, RestClient.builder().requestFactory(timeoutRequestFactory()).build(), objectMapper);
    }

    GithubAppClient(GithubProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
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
            .fromUriString("https://github.com/apps")
            .pathSegment(properties.getAppSlug(), "installations", "new")
            .queryParam("state", state)
            .encode()
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
            throw handleRestClientException("GitHub App 설치 정보 조회", e);
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
            throw handleRestClientException("GitHub App Access Token 발급", e);
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
            throw handleRestClientException("GitHub App 저장소 목록 조회", e);
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
            long exp = now + (9 * 60);

            Map<String, Object> headerMap = Map.of("alg", "RS256", "typ", "JWT");
            Map<String, Object> payloadMap = Map.of("iat", iat, "exp", exp, "iss", properties.getAppId());

            String headerJson = objectMapper.writeValueAsString(headerMap);
            String payloadJson = objectMapper.writeValueAsString(payloadMap);

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

    /**
     * PKCS#1(BEGIN RSA PRIVATE KEY) 또는 PKCS#8(BEGIN PRIVATE KEY) 형식의 키를 파싱한다.
     * 환경 변수 리터럴 줄바꿈(\n)을 정상 줄바꿈으로 치환하고 PKCS#1은 PKCS#8 DER로 래핑하여 파싱한다.
     */
    public PrivateKey parsePrivateKey(String key) throws Exception {
        String normalizedKey = key.replace("\\n", "\n").trim();
        boolean isPkcs1 = normalizedKey.contains("BEGIN RSA PRIVATE KEY");

        String cleanKey
            = normalizedKey
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] decodedKey = Base64.getDecoder().decode(cleanKey);
        byte[] pkcs8Bytes = isPkcs1 ? wrapPkcs1ToPkcs8(decodedKey) : decodedKey;

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        byte[] octetString = encodeDer(0x04, pkcs1Bytes);
        ByteBuffer buffer
            = ByteBuffer.allocate(PKCS8_VERSION.length + RSA_ALGORITHM_IDENTIFIER.length + octetString.length);
        buffer.put(PKCS8_VERSION);
        buffer.put(RSA_ALGORITHM_IDENTIFIER);
        buffer.put(octetString);
        return encodeDer(0x30, buffer.array());
    }

    private static byte[] encodeDer(int tag, byte[] content) {
        int length = content.length;
        if (length < 128) {
            byte[] result = new byte[2 + length];
            result[0] = (byte)tag;
            result[1] = (byte)length;
            System.arraycopy(content, 0, result, 2, length);
            return result;
        } else if (length < 256) {
            byte[] result = new byte[3 + length];
            result[0] = (byte)tag;
            result[1] = (byte)0x81;
            result[2] = (byte)length;
            System.arraycopy(content, 0, result, 3, length);
            return result;
        } else {
            byte[] result = new byte[4 + length];
            result[0] = (byte)tag;
            result[1] = (byte)0x82;
            result[2] = (byte)(length >> 8);
            result[3] = (byte)(length & 0xff);
            System.arraycopy(content, 0, result, 4, length);
            return result;
        }
    }

    private IntegrationException handleRestClientException(String action, RestClientException exception) {
        log.error("{}: error={}", action, exception.getMessage());
        if (exception instanceof RestClientResponseException resEx) {
            int statusCode = resEx.getStatusCode().value();
            if (statusCode >= 400 && statusCode < 500) {
                return new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, exception);
            }
            return new IntegrationException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, exception);
        }
        if (exception instanceof ResourceAccessException) {
            return new IntegrationException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, exception);
        }
        return new IntegrationException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, exception);
    }
}
