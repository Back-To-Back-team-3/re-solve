package com.backtoback.member.auth.controller;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.dto.AccessTokenResponse;
import com.backtoback.member.auth.dto.LoginCodeExchangeRequest;
import com.backtoback.member.auth.dto.TokenResponse;
import com.backtoback.member.auth.service.AuthService;
import com.backtoback.member.auth.service.GitHubLoginStart;
import com.backtoback.member.auth.service.LoginResult;
import com.backtoback.member.auth.service.TokenRefreshResult;
import com.backtoback.member.auth.store.OAuthStateStore;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.global.response.ApiResponse;
import com.backtoback.member.global.response.ErrorResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    static final String LOGIN_NONCE_COOKIE = "loginNonce";
    static final String AUTH_COOKIE_PATH = "/api/v1/auth";
    static final String USER_ID_HEADER = "X-User-Id";

    /**
     * 재발급이 이 오류로 실패하면 더 쓸 수 없는 Refresh Token 쿠키를 지운다.
     */
    private static final Set<ErrorCode> REFRESH_FAILURES
        = EnumSet.of(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, ErrorCode.AUTH_REFRESH_TOKEN_REUSED);

    private final AuthService authService;
    private final AuthProperties authProperties;

    /**
     * 1.1.1 GitHub 로그인 시작 — 브라우저 nonce 쿠키를 내려주고 GitHub 인가 화면으로 보낸다.
     */
    @GetMapping("/github")
    public ResponseEntity<Void> startGitHubLogin(@RequestParam(required = false) String redirectPath) {
        GitHubLoginStart start = authService.startGitHubLogin(redirectPath);
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .location(URI.create(start.authorizeUrl()))
            .header(HttpHeaders.SET_COOKIE, loginNonceCookie(start.browserNonce()).toString())
            .build();
    }

    /**
     * 1.1.2 GitHub 로그인 콜백 — 회원을 찾거나 가입시키고 로그인 교환 코드와 함께 프론트로 보낸다.
     * 브라우저가 직접 여는 주소이므로 실패(인가 취소 포함)도 JSON이 아니라 프론트 콜백의 {@code error}로 보낸다.
     */
    @GetMapping("/github/callback")
    public ResponseEntity<Void> completeGitHubLogin(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        @CookieValue(
            name = LOGIN_NONCE_COOKIE,
            required = false
        ) String loginNonce
    ) {
        try {
            return redirect(authService.completeGitHubLogin(code, state, error, loginNonce));
        } catch (BusinessException exception) {
            if (exception.getErrorCode().getStatus().is5xxServerError()) {
                log.warn("GitHub login callback failed: {}", exception.getErrorCode(), exception);
            }
            return redirectToCallbackError(exception.getErrorCode());
        } catch (RuntimeException exception) {
            log.error("GitHub login callback failed unexpectedly", exception);
            return redirectToCallbackError(ErrorCode.COMMON_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 1.1.3 로그인 코드 교환 — Access Token은 본문으로, Refresh Token은 쿠키로 내려준다.
     * 로그인을 시작한 브라우저의 nonce 쿠키가 있어야 하며, 성공하면 nonce 쿠키를 지운다.
     */
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenResponse>> exchangeLoginCode(
        @Valid @RequestBody LoginCodeExchangeRequest request,
        @CookieValue(
            name = LOGIN_NONCE_COOKIE,
            required = false
        ) String loginNonce
    ) {
        LoginResult result = authService.exchangeLoginCode(request.loginCode(), loginNonce);
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(result.refreshToken()).toString())
            .header(HttpHeaders.SET_COOKIE, expiredLoginNonceCookie().toString())
            .body(ApiResponse.ok(TokenResponse.from(result)));
    }

    /**
     * 1.1.4 토큰 재발급 — Refresh Token을 교체하고 새 Access Token을 내려준다.
     * 유예 시간 안의 중복 재발급은 Access Token만 내려주고 쿠키는 그대로 둔다.
     */
    @PostMapping("/token/refresh")
    public ResponseEntity<?> refresh(
        @CookieValue(
            name = REFRESH_TOKEN_COOKIE,
            required = false
        ) String refreshToken
    ) {
        TokenRefreshResult result;
        try {
            result = authService.refresh(refreshToken);
        } catch (BusinessException exception) {
            if (!REFRESH_FAILURES.contains(exception.getErrorCode())) {
                throw exception;
            }
            ErrorCode errorCode = exception.getErrorCode();
            return ResponseEntity
                .status(errorCode.getStatus())
                .header(HttpHeaders.SET_COOKIE, expiredRefreshTokenCookie().toString())
                .body(ErrorResponse.of(errorCode));
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.refreshTokenRotated()) {
            response.header(HttpHeaders.SET_COOKIE, refreshTokenCookie(result.newRefreshToken()).toString());
        }
        return response.body(ApiResponse.ok(AccessTokenResponse.from(result)));
    }

    /**
     * 1.1.5 로그아웃 — Refresh Token을 폐기하고 Access Token을 남은 수명 동안 차단한다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @RequestHeader(USER_ID_HEADER) Long memberId,
        @RequestHeader(
            name = HttpHeaders.AUTHORIZATION,
            required = false
        ) String authorization,
        @CookieValue(
            name = REFRESH_TOKEN_COOKIE,
            required = false
        ) String refreshToken
    ) {
        authService.logout(memberId, authorization, refreshToken);
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, expiredRefreshTokenCookie().toString())
            .build();
    }

    private ResponseCookie refreshTokenCookie(String refreshToken) {
        return refreshTokenCookieBuilder(refreshToken).maxAge(authProperties.refreshToken().ttl()).build();
    }

    private ResponseCookie expiredRefreshTokenCookie() {
        return refreshTokenCookieBuilder("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder refreshTokenCookieBuilder(String value) {
        return ResponseCookie
            .from(REFRESH_TOKEN_COOKIE, value)
            .httpOnly(true)
            .secure(authProperties.refreshToken().cookieSecure())
            .sameSite("Strict")
            .path(AUTH_COOKIE_PATH);
    }

    /**
     * GitHub에서 돌아오는 top-level GET에도 실리도록 {@code SameSite=Lax}를 쓴다. 수명은 OAuth state와 같다.
     */
    private ResponseCookie loginNonceCookie(String browserNonce) {
        return loginNonceCookieBuilder(browserNonce).maxAge(OAuthStateStore.TTL).build();
    }

    private ResponseCookie expiredLoginNonceCookie() {
        return loginNonceCookieBuilder("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder loginNonceCookieBuilder(String value) {
        return ResponseCookie
            .from(LOGIN_NONCE_COOKIE, value)
            .httpOnly(true)
            .secure(authProperties.refreshToken().cookieSecure())
            .sameSite("Lax")
            .path(AUTH_COOKIE_PATH);
    }

    private ResponseEntity<Void> redirectToCallbackError(ErrorCode errorCode) {
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .location(URI.create(authService.frontendCallbackError(errorCode)))
            .header(HttpHeaders.SET_COOKIE, expiredLoginNonceCookie().toString())
            .build();
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
