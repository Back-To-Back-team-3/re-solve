package com.backtoback.member.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
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
import com.backtoback.member.auth.service.LoginResult;
import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.member.domain.Member;

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
    @DisplayName("1.1.1 로그인 시작은 GitHub 인가 화면으로 302 리다이렉트한다")
    void startGitHubLoginRedirects() throws Exception {
        given(authService.startGitHubLogin("/problems")).willReturn("https://github.com/login/oauth/authorize?s=1");

        mockMvc
            .perform(get("/api/v1/auth/github").param("redirectPath", "/problems"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://github.com/login/oauth/authorize?s=1"));
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
    @DisplayName("1.1.2 콜백은 로그인 교환 코드를 붙여 프론트로 302 리다이렉트한다")
    void callbackRedirectsToFrontend() throws Exception {
        given(authService.completeGitHubLogin("code", "state-1"))
            .willReturn("http://localhost:8080/auth/callback?loginCode=lc_abc&redirectPath=/");

        mockMvc
            .perform(get("/api/v1/auth/github/callback").param("code", "code").param("state", "state-1"))
            .andExpect(status().isFound())
            .andExpect(
                header().string("Location", "http://localhost:8080/auth/callback?loginCode=lc_abc&redirectPath=/")
            );
    }

    @Test
    @DisplayName("1.1.2 state가 유효하지 않으면 401 AUTH_TOKEN_INVALID")
    void callbackRejectsInvalidState() throws Exception {
        given(authService.completeGitHubLogin("code", "bad"))
            .willThrow(new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));

        mockMvc
            .perform(get("/api/v1/auth/github/callback").param("code", "code").param("state", "bad"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("1.1.3 코드 교환은 Access Token을 본문으로, Refresh Token을 HttpOnly 쿠키로 준다")
    void exchangeLoginCodeReturnsTokens() throws Exception {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", 7L);
        given(authService.exchangeLoginCode("lc_abc"))
            .willReturn(new LoginResult("access-token", 3600L, "refresh-token", member, true));

        mockMvc
            .perform(
                post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"loginCode\":\"lc_abc\"}")
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
                    .string(
                        "Set-Cookie",
                        allOf(
                            containsString("refreshToken=refresh-token"),
                            containsString("Path=/api/v1/auth"),
                            containsString("Max-Age=1209600"),
                            containsString("Secure"),
                            containsString("HttpOnly"),
                            containsString("SameSite=Strict")
                        )
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
    @DisplayName("1.1.3 이미 쓴 로그인 코드는 401 AUTH_LOGIN_CODE_INVALID")
    void exchangeLoginCodeRejectsUsedCode() throws Exception {
        given(authService.exchangeLoginCode("lc_used"))
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
}
