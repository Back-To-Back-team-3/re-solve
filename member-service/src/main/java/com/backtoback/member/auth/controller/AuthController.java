package com.backtoback.member.auth.controller;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.dto.LoginCodeExchangeRequest;
import com.backtoback.member.auth.dto.TokenResponse;
import com.backtoback.member.auth.service.AuthService;
import com.backtoback.member.auth.service.LoginResult;
import com.backtoback.member.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final AuthProperties authProperties;

    /**
     * 1.1.1 GitHub 로그인 시작 — GitHub 인가 화면으로 보낸다.
     */
    @GetMapping("/github")
    public ResponseEntity<Void> startGitHubLogin(@RequestParam(required = false) String redirectPath) {
        String authorizeUrl = authService.startGitHubLogin(redirectPath);
        return redirect(authorizeUrl);
    }

    /**
     * 1.1.2 GitHub 로그인 콜백 — 회원을 찾거나 가입시키고 로그인 교환 코드와 함께 프론트로 보낸다.
     */
    @GetMapping("/github/callback")
    public ResponseEntity<Void> completeGitHubLogin(@RequestParam String code, @RequestParam String state) {
        String frontendCallbackUrl = authService.completeGitHubLogin(code, state);
        return redirect(frontendCallbackUrl);
    }

    /**
     * 1.1.3 로그인 코드 교환 — Access Token은 본문으로, Refresh Token은 쿠키로 내려준다.
     */
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenResponse>> exchangeLoginCode(
        @Valid @RequestBody LoginCodeExchangeRequest request
    ) {
        LoginResult result = authService.exchangeLoginCode(request.loginCode());
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(result.refreshToken()).toString())
            .body(ApiResponse.ok(TokenResponse.from(result)));
    }

    private ResponseCookie refreshTokenCookie(String refreshToken) {
        return refreshTokenCookieBuilder(refreshToken).maxAge(authProperties.refreshToken().ttl()).build();
    }

    private ResponseCookie.ResponseCookieBuilder refreshTokenCookieBuilder(String value) {
        return ResponseCookie
            .from(REFRESH_TOKEN_COOKIE, value)
            .httpOnly(true)
            .secure(authProperties.refreshToken().cookieSecure())
            .sameSite("Strict")
            .path(REFRESH_TOKEN_COOKIE_PATH);
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
