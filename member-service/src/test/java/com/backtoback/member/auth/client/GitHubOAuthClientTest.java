package com.backtoback.member.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.member.auth.AuthPropertiesFixture;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;

class GitHubOAuthClientTest {

    private static final String TOKEN_URI = "https://github.com/login/oauth/access_token";

    private MockRestServiceServer server;
    private GitHubOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubOAuthClient(AuthPropertiesFixture.create(), builder.build());
    }

    @Test
    @DisplayName("인가 주소에 client_id·redirect_uri·최소 scope·state를 담는다")
    void buildsAuthorizeUrl() {
        String url = client.buildAuthorizeUrl("state-1");

        assertThat(url)
            .startsWith("https://github.com/login/oauth/authorize?")
            .contains("client_id=client-id")
            .contains("redirect_uri=https://api.resolve.test/api/v1/auth/github/callback")
            .contains("scope=read:user%20user:email")
            .contains("state=state-1");
    }

    @Test
    @DisplayName("인가 코드를 교환해 사용자 정보와 확인된 기본 이메일을 가져온다")
    void fetchesUserWithPrimaryVerifiedEmail() {
        server
            .expect(requestTo(TOKEN_URI))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(Map.of("code", "auth-code")))
            .andRespond(
                withSuccess("{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON)
            );
        server
            .expect(requestTo("https://api.github.com/user"))
            .andExpect(header("Authorization", "Bearer gho_x"))
            .andRespond(
                withSuccess(
                    "{\"id\":1001,\"login\":\"kim-dev\",\"avatar_url\":\"https://avatars.example/1001\"}",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo("https://api.github.com/user/emails"))
            .andRespond(
                withSuccess(
                    "[{\"email\":\"old@example.com\",\"primary\":false,\"verified\":true},"
                        + "{\"email\":\"kim@example.com\",\"primary\":true,\"verified\":true}]",
                    MediaType.APPLICATION_JSON
                )
            );

        GitHubUser user = client.fetchUser("auth-code");

        assertThat(user).isEqualTo(new GitHubUser(1001L, "kim-dev", "kim@example.com", "https://avatars.example/1001"));
        server.verify();
    }

    @Test
    @DisplayName("기본 이메일이 확인되지 않았으면 이메일 없이 가입한다")
    void leavesEmailEmptyWhenPrimaryIsUnverified() {
        server
            .expect(requestTo(TOKEN_URI))
            .andRespond(withSuccess("{\"access_token\":\"gho_x\"}", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"id\":1001,\"login\":\"kim-dev\"}", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("https://api.github.com/user/emails"))
            .andRespond(
                withSuccess(
                    "[{\"email\":\"kim@example.com\",\"primary\":true,\"verified\":false}]",
                    MediaType.APPLICATION_JSON
                )
            );

        assertThat(client.fetchUser("auth-code").email()).isNull();
    }

    @Test
    @DisplayName("GitHub이 200과 함께 error를 돌려주면 AUTH_OAUTH_FAILED")
    void failsWhenCodeIsRejected() {
        server
            .expect(requestTo(TOKEN_URI))
            .andRespond(withSuccess("{\"error\":\"bad_verification_code\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchUser("bad-code"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_OAUTH_FAILED);
    }

    @Test
    @DisplayName("GitHub API가 실패하면 AUTH_OAUTH_FAILED")
    void failsWhenGitHubIsDown() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchUser("auth-code"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_OAUTH_FAILED);
    }
}
