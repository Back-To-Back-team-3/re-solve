package com.backtoback.member.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.service.AuthService;
import com.backtoback.member.auth.service.GitHubLoginStart;
import com.backtoback.member.auth.service.LoginResult;
import com.backtoback.member.auth.service.TokenRefreshResult;
import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.member.domain.Member;

import jakarta.servlet.http.Cookie;

@WebMvcTest(
    controllers = AuthController.class,
    properties = {
        "resolve.auth.github.client-id=client-id",
        "resolve.auth.github.client-secret=client-secret",
        "resolve.auth.jwt.secret=test-jwt-secret-key-must-be-at-least-32-bytes"
    }
)
@Import(
    {
        SecurityConfig.class,
        AuthControllerTest.AuthPropertiesConfig.class
    }
)
class AuthControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(AuthProperties.class)
    static class AuthPropertiesConfig {

    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("1.1.1 로그인 시작은 브라우저 nonce 쿠키(SameSite=Lax)를 주고 GitHub 인가 화면으로 302 리다이렉트한다")
    void startGitHubLoginRedirectsWithNonceCookie() throws Exception {
        given(authService.startGitHubLogin("/problems"))
            .willReturn(new GitHubLoginStart("https://github.com/login/oauth/authorize?s=1", "nonce-1"));

        mockMvc
            .perform(get("/api/v1/auth/github").param("redirectPath", "/problems"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://github.com/login/oauth/authorize?s=1"))
            .andExpect(
                header()
                    .string(
                        "Set-Cookie",
                        allOf(
                            containsString("loginNonce=nonce-1"),
                            containsString("Path=/api/v1/auth"),
                            containsString("Max-Age=600"),
                            containsString("HttpOnly"),
                            containsString("SameSite=Lax")
                        )
                    )
            );
    }

    @Test
    @DisplayName("1.1.1 허용되지 않은 이동 경로는 400 COMMON_INVALID_REQUEST")
    void startGitHubLoginRejectsInvalidRedirect() throws Exception {
        given(authService.startGitHubLogin("https://evil.example"))
            .willThrow(new BusinessException(ErrorCode.COMMON_INVALID_REQUEST));

        mockMvc
            .perform(get("/api/v1/auth/github").param("redirectPath", "https://evil.example"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("1.1.2 콜백은 nonce 쿠키를 서비스에 넘기고 로그인 교환 코드를 붙여 프론트로 302 리다이렉트한다")
    void callbackRedirectsToFrontend() throws Exception {
        given(authService.completeGitHubLogin("code", "state-1", null, "nonce-1"))
            .willReturn("http://localhost:8080/auth/callback?loginCode=lc_abc&redirectPath=/");

        mockMvc
            .perform(
                get("/api/v1/auth/github/callback")
                    .param("code", "code")
                    .param("state", "state-1")
                    .cookie(new Cookie("loginNonce", "nonce-1"))
            )
            .andExpect(status().isFound())
            .andExpect(
                header().string("Location", "http://localhost:8080/auth/callback?loginCode=lc_abc&redirectPath=/")
            );
    }

    @Test
    @DisplayName("1.1.2 콜백 실패는 JSON이 아니라 프론트 콜백 ?error=코드로 302 리다이렉트하고 nonce 쿠키를 지운다")
    void callbackFailureRedirectsWithError() throws Exception {
        given(authService.completeGitHubLogin("code", "bad", null, null))
            .willThrow(new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
        given(authService.frontendCallbackError(ErrorCode.AUTH_TOKEN_INVALID))
            .willReturn("http://localhost:8080/auth/callback?error=AUTH_TOKEN_INVALID");

        mockMvc
            .perform(get("/api/v1/auth/github/callback").param("code", "code").param("state", "bad"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:8080/auth/callback?error=AUTH_TOKEN_INVALID"))
            .andExpect(
                header().string("Set-Cookie", allOf(containsString("loginNonce="), containsString("Max-Age=0")))
            );
    }

    @Test
    @DisplayName("1.1.2 GitHub 인가 취소(code 없이 error=access_denied)도 프론트 콜백으로 302 리다이렉트한다")
    void callbackHandlesCancel() throws Exception {
        given(authService.completeGitHubLogin(null, "state-1", "access_denied", "nonce-1"))
            .willThrow(new BusinessException(ErrorCode.AUTH_OAUTH_DENIED));
        given(authService.frontendCallbackError(ErrorCode.AUTH_OAUTH_DENIED))
            .willReturn("http://localhost:8080/auth/callback?error=AUTH_OAUTH_DENIED");

        mockMvc
            .perform(
                get("/api/v1/auth/github/callback")
                    .param("error", "access_denied")
                    .param("state", "state-1")
                    .cookie(new Cookie("loginNonce", "nonce-1"))
            )
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "http://localhost:8080/auth/callback?error=AUTH_OAUTH_DENIED"));
    }

    @Test
    @DisplayName("1.1.2 예상하지 못한 오류도 프론트 콜백 ?error=COMMON_INTERNAL_SERVER_ERROR로 보낸다")
    void callbackUnexpectedFailureRedirects() throws Exception {
        given(authService.completeGitHubLogin("code", "state-1", null, "nonce-1"))
            .willThrow(new IllegalStateException("redis down"));
        given(authService.frontendCallbackError(ErrorCode.COMMON_INTERNAL_SERVER_ERROR))
            .willReturn("http://localhost:8080/auth/callback?error=COMMON_INTERNAL_SERVER_ERROR");

        mockMvc
            .perform(
                get("/api/v1/auth/github/callback")
                    .param("code", "code")
                    .param("state", "state-1")
                    .cookie(new Cookie("loginNonce", "nonce-1"))
            )
            .andExpect(status().isFound())
            .andExpect(
                header().string("Location", "http://localhost:8080/auth/callback?error=COMMON_INTERNAL_SERVER_ERROR")
            );
    }

    @Test
    @DisplayName("1.1.3 코드 교환은 Access Token을 본문으로, Refresh Token을 HttpOnly 쿠키로 주고 nonce 쿠키를 지운다")
    void exchangeLoginCodeReturnsTokens() throws Exception {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", 7L);
        given(authService.exchangeLoginCode("lc_abc", "nonce-1"))
            .willReturn(new LoginResult("access-token", 3600L, "refresh-token", member, true));

        mockMvc
            .perform(
                post("/api/v1/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"loginCode\":\"lc_abc\"}")
                    .cookie(new Cookie("loginNonce", "nonce-1"))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token"))
            .andExpect(jsonPath("$.data.expiresIn").value(3600))
            .andExpect(jsonPath("$.data.member.memberId").value("7"))
            .andExpect(jsonPath("$.data.member.nickname").value("kim-dev"))
            .andExpect(jsonPath("$.data.member.role").value("USER"))
            .andExpect(jsonPath("$.data.member.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.member.isNewMember").value(true))
            .andExpect(
                header()
                    .stringValues(
                        "Set-Cookie",
                        hasItem(
                            allOf(
                                containsString("refreshToken=refresh-token"),
                                containsString("Path=/api/v1/auth"),
                                containsString("Max-Age=1209600"),
                                containsString("Secure"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Strict")
                            )
                        )
                    )
            )
            .andExpect(
                header()
                    .stringValues(
                        "Set-Cookie",
                        hasItem(allOf(containsString("loginNonce="), containsString("Max-Age=0")))
                    )
            );
    }

    @Test
    @DisplayName("1.1.3 로그인 코드가 비어 있으면 400 COMMON_INVALID_REQUEST")
    void exchangeLoginCodeValidatesBody() throws Exception {
        mockMvc
            .perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"loginCode\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").value("loginCode"));
    }

    @Test
    @DisplayName("1.1.3 이미 쓴 코드이거나 다른 브라우저면 401 AUTH_LOGIN_CODE_INVALID")
    void exchangeLoginCodeRejectsUsedCode() throws Exception {
        given(authService.exchangeLoginCode("lc_used", null))
            .willThrow(new BusinessException(ErrorCode.AUTH_LOGIN_CODE_INVALID));

        mockMvc
            .perform(
                post("/api/v1/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"loginCode\":\"lc_used\"}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_LOGIN_CODE_INVALID"));
    }

    @Test
    @DisplayName("없는 경로·맞지 않는 메서드는 500이 아니라 원래 상태(404·405)로 응답한다")
    void springClientErrorsKeepTheirStatus() throws Exception {
        mockMvc.perform(get("/api/v1/auth/unknown")).andExpect(status().isNotFound());
        mockMvc
            .perform(get("/api/v1/auth/token"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("숫자가 아닌 X-User-Id는 500이 아니라 401 AUTH_TOKEN_INVALID")
    void nonNumericUserIdIsUnauthorized() throws Exception {
        mockMvc
            .perform(post("/api/v1/auth/logout").header("X-User-Id", "abc").header("Authorization", "Bearer access"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("1.1.4 재발급은 새 Access Token을 주고 교체된 Refresh Token 쿠키를 설정한다")
    void refreshReturnsAccessTokenAndRotatedCookie() throws Exception {
        given(authService.refresh("old-refresh"))
            .willReturn(new TokenRefreshResult("new-access", 3600L, "new-refresh"));

        mockMvc
            .perform(post("/api/v1/auth/token/refresh").cookie(new Cookie("refreshToken", "old-refresh")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("new-access"))
            .andExpect(jsonPath("$.data.expiresIn").value(3600))
            .andExpect(
                header()
                    .string(
                        "Set-Cookie",
                        allOf(
                            containsString("refreshToken=new-refresh"),
                            containsString("Max-Age=1209600"),
                            containsString("HttpOnly")
                        )
                    )
            );
    }

    @Test
    @DisplayName("1.1.4 유예 시간 안의 중복 재발급은 Access Token만 주고 쿠키는 그대로 둔다")
    void refreshWithinGraceKeepsCookie() throws Exception {
        given(authService.refresh("just-rotated")).willReturn(new TokenRefreshResult("new-access", 3600L, null));

        mockMvc
            .perform(post("/api/v1/auth/token/refresh").cookie(new Cookie("refreshToken", "just-rotated")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("new-access"))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("1.1.4 재발급 실패(INVALID·REUSED)는 401과 함께 Refresh Token 쿠키를 지운다")
    void refreshFailureClearsCookie() throws Exception {
        given(authService.refresh(null)).willThrow(new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));
        given(authService.refresh("stolen")).willThrow(new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_REUSED));

        mockMvc
            .perform(post("/api/v1/auth/token/refresh"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_INVALID"))
            .andExpect(
                header().string("Set-Cookie", allOf(containsString("refreshToken="), containsString("Max-Age=0")))
            );

        mockMvc
            .perform(post("/api/v1/auth/token/refresh").cookie(new Cookie("refreshToken", "stolen")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_TOKEN_REUSED"))
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    @DisplayName("1.1.5 로그아웃은 204와 함께 Refresh Token 쿠키를 지운다")
    void logoutClearsCookie() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/auth/logout")
                    .header("X-User-Id", "7")
                    .header("Authorization", "Bearer access")
                    .cookie(new Cookie("refreshToken", "refresh"))
            )
            .andExpect(status().isNoContent())
            .andExpect(
                header().string("Set-Cookie", allOf(containsString("refreshToken="), containsString("Max-Age=0")))
            );

        verify(authService).logout(7L, "Bearer access", "refresh");
    }

    @Test
    @DisplayName("1.1.5 Gateway 인증 정보(X-User-Id)가 없으면 401 AUTH_TOKEN_INVALID")
    void logoutRequiresAuthenticatedUser() throws Exception {
        mockMvc
            .perform(post("/api/v1/auth/logout").header("Authorization", "Bearer access"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        verify(authService, never()).logout(any(), any(), any());
    }
}
