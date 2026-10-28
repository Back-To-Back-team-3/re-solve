package com.backtoback.integration.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.integration.github.client.dto.GithubInstallationResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationTokenResponse;
import com.backtoback.integration.github.client.dto.GithubRepositoriesResponse;
import com.backtoback.integration.github.config.GithubProperties;

class GithubAppClientTest {

    private GithubProperties properties;
    private GithubAppClient client;
    private MockRestServiceServer server;
    private String testPrivateKeyPem;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair pair = keyGen.generateKeyPair();
        testPrivateKeyPem = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());

        properties = new GithubProperties();
        properties.setAppId("12345");
        properties.setAppSlug("resolve-test-app");
        properties.setPrivateKey(testPrivateKeyPem);
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubAppClient(properties, builder.build());
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
}
