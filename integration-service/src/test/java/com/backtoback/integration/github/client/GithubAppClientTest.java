package com.backtoback.integration.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.integration.github.client.dto.GithubCommitResultResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationTokenResponse;
import com.backtoback.integration.github.client.dto.GithubRepositoriesResponse;
import com.backtoback.integration.github.config.GithubProperties;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

import tools.jackson.databind.ObjectMapper;

class GithubAppClientTest {

    private GithubProperties properties;
    private GithubAppClient client;
    private MockRestServiceServer server;
    private String testPrivateKeyPem;
    private KeyPair pair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        pair = keyGen.generateKeyPair();
        testPrivateKeyPem = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());

        properties = new GithubProperties();
        properties.setAppId("12345");
        properties.setAppSlug("resolve-test-app");
        properties.setPrivateKey(testPrivateKeyPem);
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubAppClient(properties, builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("설치 URL 생성 시 app-slug와 state 파라미터가 포함된다")
    void buildInstallUrl() {
        String url = client.buildInstallUrl("test-state-123");
        assertThat(url).isEqualTo("https://github.com/apps/resolve-test-app/installations/new?state=test-state-123");
    }

    @Test
    @DisplayName("JWT 생성 시 3개의 세그먼트로 구성된 올바른 서명 토큰을 반환한다")
    void createAppJwt() {
        String jwt = client.createAppJwt();
        assertThat(jwt).isNotNull();
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
    }

    @Test
    @DisplayName("개인키 문자열에 헤더/푸터 및 리터럴 \\n 줄바꿈이 포함되어 있어도 정상 파싱된다")
    void createAppJwtWithPemHeadersAndEscapedNewlines() {
        String pemWithHeaders = "-----BEGIN PRIVATE KEY-----\\n" + testPrivateKeyPem + "\\n-----END PRIVATE KEY-----";
        properties.setPrivateKey(pemWithHeaders);

        String jwt = client.createAppJwt();
        assertThat(jwt).isNotNull();
        assertThat(jwt.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("설치 정보 조회가 성공하면 올바른 계정 정보와 ID를 반환한다")
    void getInstallationSuccess() {
        server
            .expect(requestTo("https://api.github.com/app/installations/58291032"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
            .andRespond(withSuccess("""
                {
                  "id": 58291032,
                  "account": {
                    "id": 9999,
                    "login": "kim-dev",
                    "type": "User"
                  }
                }
                """, MediaType.APPLICATION_JSON));

        GithubInstallationResponse response = client.getInstallation(58291032L);

        assertThat(response.id()).isEqualTo(58291032L);
        assertThat(response.account().login()).isEqualTo("kim-dev");
        server.verify();
    }

    @Test
    @DisplayName("GitHub API에서 404가 반환되면 GITHUB_INSTALLATION_INVALID 예외가 발생한다")
    void getInstallationReturns404ThrowsInstallationInvalid() {
        server
            .expect(requestTo("https://api.github.com/app/installations/58291032"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getInstallation(58291032L))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);
    }

    @Test
    @DisplayName("GitHub API에서 500 서버 오류가 반환되면 COMMON_DEPENDENCY_UNAVAILABLE 예외가 발생한다")
    void getInstallationReturns500ThrowsDependencyUnavailable() {
        server
            .expect(requestTo("https://api.github.com/app/installations/58291032"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.getInstallation(58291032L))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("설치 Access Token 발급이 성공하면 토큰 문자열을 반환한다")
    void createInstallationAccessTokenSuccess() {
        server
            .expect(requestTo("https://api.github.com/app/installations/58291032/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""
                {
                  "token": "ghs_test_token_12345",
                  "expires_at": "2026-10-28T16:00:00Z"
                }
                """, MediaType.APPLICATION_JSON));

        GithubInstallationTokenResponse response = client.createInstallationAccessToken(58291032L);

        assertThat(response.token()).isEqualTo("ghs_test_token_12345");
        server.verify();
    }

    @Test
    @DisplayName("설치된 저장소 목록 조회가 성공하면 저장소 정보를 반환한다")
    void getInstallationRepositoriesSuccess() {
        server
            .expect(requestTo("https://api.github.com/installation/repositories"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer ghs_test_token_12345"))
            .andRespond(withSuccess("""
                {
                  "total_count": 1,
                  "repositories": [
                    {
                      "id": 111,
                      "name": "algorithm-solutions",
                      "full_name": "kim-dev/algorithm-solutions",
                      "owner": {
                        "id": 9999,
                        "login": "kim-dev"
                      }
                    }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        GithubRepositoriesResponse response = client.getInstallationRepositories("ghs_test_token_12345");

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.repositories()).hasSize(1);
        assertThat(response.repositories().get(0).name()).isEqualTo("algorithm-solutions");
        assertThat(response.repositories().get(0).owner().login()).isEqualTo("kim-dev");
        server.verify();
    }

    @Test
    @DisplayName("기존 파일이 있으면 Contents API 조회로 sha를 반환한다")
    void findContentShaWhenExists() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/Lv1/1-two-sum/solution.py"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer ghs_token"))
            .andRespond(withSuccess("{\"sha\": \"abc123\"}", MediaType.APPLICATION_JSON));

        assertThat(client.findContentSha("ghs_token", "kim-dev", "solutions", "Lv1/1-two-sum/solution.py"))
            .contains("abc123");
        server.verify();
    }

    @Test
    @DisplayName("파일이 없어 404가 오면 빈 값을 반환한다")
    void findContentShaWhenNotFound() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/new.py"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findContentSha("ghs_token", "kim-dev", "solutions", "new.py")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("파일 sha 조회에서 401이 오면 GithubApiException(401)으로 변환한다")
    void findContentShaUnauthorized() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/a.py"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.findContentSha("ghs_token", "kim-dev", "solutions", "a.py"))
            .isInstanceOfSatisfying(GithubApiException.class, e -> assertThat(e.isUnauthorized()).isTrue());
    }

    @Test
    @DisplayName("파일 커밋 시 Base64 내용과 기존 sha를 담아 PUT하고 커밋 sha를 반환한다")
    void putContentSuccess() {
        String expectedContent = Base64.getEncoder().encodeToString("print(1)".getBytes());
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/a.py"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer ghs_token"))
            .andExpect(content().string(containsString("\"content\":\"" + expectedContent + "\"")))
            .andExpect(content().string(containsString("\"sha\":\"old-sha\"")))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"commit\": {\"sha\": \"new-commit-sha\"}}")
            );

        GithubCommitResultResponse response
            = client.putContent("ghs_token", "kim-dev", "solutions", "a.py", "msg", "print(1)", "old-sha");

        assertThat(response.commitSha()).isEqualTo("new-commit-sha");
        server.verify();
    }

    @Test
    @DisplayName("신규 파일 커밋은 sha 필드를 보내지 않는다")
    void putContentWithoutShaOmitsShaField() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/a.py"))
            .andExpect(content().string(not(containsString("\"sha\""))))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"commit\": {\"sha\": \"c1\"}}")
            );

        assertThat(client.putContent("ghs_token", "kim-dev", "solutions", "a.py", "msg", "x", null).commitSha())
            .isEqualTo("c1");
        server.verify();
    }

    @Test
    @DisplayName("Rate Limit 응답(403)은 x-ratelimit-reset 헤더의 초기화 시각을 담아 변환한다")
    void putContentRateLimited() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/a.py"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).header("x-ratelimit-reset", "1893456000"));

        assertThatThrownBy(() -> client.putContent("ghs_token", "kim-dev", "solutions", "a.py", "m", "x", null))
            .isInstanceOfSatisfying(GithubApiException.class, e -> {
                assertThat(e.isRateLimited()).isTrue();
                assertThat(e.getRateLimitResetAt()).isEqualTo(Instant.ofEpochSecond(1893456000L));
            });
    }

    @Test
    @DisplayName("sha 불일치(409)는 상태 코드를 보존해 변환한다")
    void putContentConflict() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/a.py"))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> client.putContent("t", "kim-dev", "solutions", "a.py", "m", "x", "s"))
            .isInstanceOfSatisfying(GithubApiException.class, e -> assertThat(e.isConflict()).isTrue());
    }

    @Test
    @DisplayName("서버 오류(5xx)는 상태 코드를 보존해 변환한다")
    void putContentServerError() {
        server
            .expect(requestTo("https://api.github.com/repos/kim-dev/solutions/contents/a.py"))
            .andRespond(withServerError());
        assertThatThrownBy(() -> client.putContent("t", "kim-dev", "solutions", "a.py", "m", "x", "s"))
            .isInstanceOfSatisfying(GithubApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(500));
    }
}
