package com.backtoback.member.auth.client;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub OAuth2 인가 코드 교환과 사용자 정보 조회. 로그인 인가 범위는 {@code read:user}, {@code user:email}로 제한한다.
 */
@Component
public class GitHubOAuthClient {

    static final String LOGIN_SCOPE = "read:user user:email";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String GITHUB_API_VERSION = "2022-11-28";

    private final AuthProperties.GitHub properties;
    private final RestClient restClient;

    @Autowired
    public GitHubOAuthClient(AuthProperties authProperties) {
        this(authProperties, RestClient.builder().requestFactory(timeoutRequestFactory()).build());
    }

    GitHubOAuthClient(AuthProperties authProperties, RestClient restClient) {
        this.properties = authProperties.github();
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return requestFactory;
    }

    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder
            .fromUriString(properties.authorizeUri())
            .queryParam("client_id", properties.clientId())
            .queryParam("redirect_uri", properties.redirectUri())
            .queryParam("scope", LOGIN_SCOPE)
            .queryParam("state", state)
            .queryParam("allow_signup", "true")
            .encode()
            .build()
            .toUriString();
    }

    /**
     * 인가 코드를 GitHub 액세스 토큰으로 바꾼 뒤 사용자 정보를 조회한다. GitHub 토큰은 저장하지 않는다.
     */
    public GitHubUser fetchUser(String authorizationCode) {
        try {
            String gitHubAccessToken = exchangeCode(authorizationCode);
            UserResponse user = getUser(gitHubAccessToken);
            String email = findPrimaryVerifiedEmail(gitHubAccessToken);
            return new GitHubUser(user.id(), user.login(), email, user.avatarUrl());
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.AUTH_OAUTH_FAILED, exception);
        }
    }

    private String exchangeCode(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", authorizationCode);
        form.add("redirect_uri", properties.redirectUri());

        TokenResponse response
            = restClient
                .post()
                .uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        // GitHub은 잘못된 코드에도 200으로 error 필드를 돌려준다.
        if (response == null || response.accessToken() == null || response.error() != null) {
            throw new BusinessException(ErrorCode.AUTH_OAUTH_FAILED);
        }
        return response.accessToken();
    }

    private UserResponse getUser(String gitHubAccessToken) {
        UserResponse user = apiGet("/user", gitHubAccessToken, UserResponse.class);
        if (user == null || user.id() == null || user.login() == null) {
            throw new BusinessException(ErrorCode.AUTH_OAUTH_FAILED);
        }
        return user;
    }

    private String findPrimaryVerifiedEmail(String gitHubAccessToken) {
        EmailResponse[] emails = apiGet("/user/emails", gitHubAccessToken, EmailResponse[].class);
        if (emails == null) {
            return null;
        }
        return List
            .of(emails)
            .stream()
            .filter(Objects::nonNull)
            .filter(email -> email.primary() && email.verified())
            .map(EmailResponse::email)
            .findFirst()
            .orElse(null);
    }

    private <T> T apiGet(String path, String gitHubAccessToken, Class<T> responseType) {
        return restClient
            .get()
            .uri(properties.apiBaseUri() + path)
            .accept(MediaType.parseMediaType("application/vnd.github+json"))
            .header("Authorization", "Bearer " + gitHubAccessToken)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .retrieve()
            .body(responseType);
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("error") String error) {
    }

    record UserResponse(Long id, String login, @JsonProperty("avatar_url") String avatarUrl) {
    }

    record EmailResponse(String email, boolean primary, boolean verified) {
    }
}
