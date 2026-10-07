package com.backtoback.member.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.member.auth.AuthPropertiesFixture;
import com.backtoback.member.auth.client.GitHubOAuthClient;
import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.store.AccessTokenBlocklist;
import com.backtoback.member.auth.store.LoginCodeStore;
import com.backtoback.member.auth.store.LoginCodeStore.LoginCodeClaim;
import com.backtoback.member.auth.store.OAuthStateStore;
import com.backtoback.member.auth.store.OAuthStateStore.OAuthState;
import com.backtoback.member.auth.store.RefreshTokenStore;
import com.backtoback.member.auth.store.RefreshTokenStore.RotationOutcome;
import com.backtoback.member.auth.store.RefreshTokenStore.RotationResult;
import com.backtoback.member.auth.token.AccessTokenProvider;
import com.backtoback.member.auth.token.SecureTokenGenerator;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.service.GitHubMemberRegistration;
import com.backtoback.member.member.service.MemberService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Mock
    private SecureTokenGenerator tokenGenerator;
    @Mock
    private OAuthStateStore oAuthStateStore;
    @Mock
    private LoginCodeStore loginCodeStore;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private GitHubOAuthClient gitHubOAuthClient;
    @Mock
    private AccessTokenProvider accessTokenProvider;
    @Mock
    private MemberService memberService;
    @Mock
    private AccessTokenBlocklist accessTokenBlocklist;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthProperties properties = AuthPropertiesFixture.create();
        authService
            = new AuthService(
                properties,
                new RedirectPathPolicy(properties),
                tokenGenerator,
                oAuthStateStore,
                loginCodeStore,
                refreshTokenStore,
                accessTokenBlocklist,
                gitHubOAuthClient,
                accessTokenProvider,
                memberService,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
    }

    @Test
    @DisplayName("재발급: Refresh Token을 교체하고 새 Access Token과 새 Refresh Token을 돌려준다")
    void refreshRotatesToken() {
        Member member = memberWithId(7L);
        given(refreshTokenStore.rotate("old-refresh"))
            .willReturn(new RotationResult(RotationOutcome.ROTATED, 7L, "new-refresh"));
        given(memberService.findMember(7L)).willReturn(Optional.of(member));
        given(accessTokenProvider.issue(member)).willReturn("new-access");
        given(accessTokenProvider.expiresInSeconds()).willReturn(3600L);

        TokenRefreshResult result = authService.refresh("old-refresh");

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.expiresIn()).isEqualTo(3600L);
        assertThat(result.newRefreshToken()).isEqualTo("new-refresh");
        assertThat(result.refreshTokenRotated()).isTrue();
    }

    @Test
    @DisplayName("재발급: 유예 시간 안의 중복 요청은 Access Token만 주고 Refresh Token은 바꾸지 않는다")
    void refreshWithinGraceIssuesAccessTokenOnly() {
        Member member = memberWithId(7L);
        given(refreshTokenStore.rotate("just-rotated")).willReturn(new RotationResult(RotationOutcome.GRACE, 7L, null));
        given(memberService.findMember(7L)).willReturn(Optional.of(member));
        given(accessTokenProvider.issue(member)).willReturn("new-access");

        TokenRefreshResult result = authService.refresh("just-rotated");

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshTokenRotated()).isFalse();
    }

    @Test
    @DisplayName("재발급: 쿠키가 없으면 AUTH_REFRESH_TOKEN_INVALID")
    void refreshWithoutCookieFails() {
        assertThatThrownBy(() -> authService.refresh(null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        verifyNoInteractions(refreshTokenStore);
    }

    @Test
    @DisplayName("재발급: 없거나 만료된 토큰은 AUTH_REFRESH_TOKEN_INVALID")
    void refreshWithUnknownTokenFails() {
        given(refreshTokenStore.rotate("unknown")).willReturn(new RotationResult(RotationOutcome.INVALID, null, null));

        assertThatThrownBy(() -> authService.refresh("unknown"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        verifyNoInteractions(accessTokenProvider);
    }

    @Test
    @DisplayName("재발급: 유예가 지난 교체 토큰을 다시 쓰면 AUTH_REFRESH_TOKEN_REUSED")
    void refreshWithReusedTokenFails() {
        given(refreshTokenStore.rotate("stolen")).willReturn(new RotationResult(RotationOutcome.REUSED, 7L, null));

        assertThatThrownBy(() -> authService.refresh("stolen"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_REUSED);
        verifyNoInteractions(accessTokenProvider);
    }

    @Test
    @DisplayName("재발급: 탈퇴한 회원이면 방금 만든 Refresh Token을 폐기하고 AUTH_REFRESH_TOKEN_INVALID")
    void refreshForWithdrawnMemberFails() {
        Member withdrawn = memberWithId(7L);
        ReflectionTestUtils.setField(withdrawn, "status", MemberStatus.WITHDRAWN);
        given(refreshTokenStore.rotate("old-refresh"))
            .willReturn(new RotationResult(RotationOutcome.ROTATED, 7L, "new-refresh"));
        given(memberService.findMember(7L)).willReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> authService.refresh("old-refresh"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        verify(refreshTokenStore).revoke(7L, "new-refresh");
    }

    @Test
    @DisplayName("로그아웃: Access Token을 남은 수명만큼 차단하고 Refresh Token을 폐기한다")
    void logoutBlocksAccessTokenAndRevokesRefreshToken() {
        given(accessTokenProvider.decode("access")).willReturn(Optional.of(accessJwt("7", NOW.plusSeconds(1200))));

        authService.logout(7L, "Bearer access", "refresh");

        verify(accessTokenBlocklist).block("jti-1", Duration.ofSeconds(1200));
        verify(refreshTokenStore).revoke(7L, "refresh");
    }

    @Test
    @DisplayName("로그아웃: Refresh Token 쿠키가 없어도 Access Token은 차단한다")
    void logoutWithoutRefreshCookie() {
        given(accessTokenProvider.decode("access")).willReturn(Optional.of(accessJwt("7", NOW.plusSeconds(60))));

        authService.logout(7L, "Bearer access", null);

        verify(accessTokenBlocklist).block("jti-1", Duration.ofSeconds(60));
        verifyNoInteractions(refreshTokenStore);
    }

    @Test
    @DisplayName("로그아웃: Bearer 토큰이 없거나 요청자와 토큰 주인이 다르면 AUTH_TOKEN_INVALID")
    void logoutRejectsMissingOrForeignToken() {
        assertThatThrownBy(() -> authService.logout(7L, null, "refresh"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);

        given(accessTokenProvider.decode("other")).willReturn(Optional.of(accessJwt("8", NOW.plusSeconds(60))));
        assertThatThrownBy(() -> authService.logout(7L, "Bearer other", "refresh"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        verifyNoInteractions(accessTokenBlocklist, refreshTokenStore);
    }

    private Jwt accessJwt(String subject, Instant expiresAt) {
        return Jwt
            .withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .jti("jti-1")
            .issuedAt(NOW.minusSeconds(60))
            .expiresAt(expiresAt)
            .build();
    }

    @Test
    @DisplayName("로그인 시작: state·이동 경로·브라우저 nonce 해시를 저장하고 nonce를 함께 돌려준다")
    void startGitHubLoginStoresStateBoundToBrowser() {
        given(tokenGenerator.generate()).willReturn("state-1", "nonce-1");
        given(tokenGenerator.hash("nonce-1")).willReturn("hash-1");
        given(gitHubOAuthClient.buildAuthorizeUrl("state-1")).willReturn("https://github.com/login/oauth/authorize?x");

        GitHubLoginStart start = authService.startGitHubLogin("/problems");

        assertThat(start.authorizeUrl()).isEqualTo("https://github.com/login/oauth/authorize?x");
        assertThat(start.browserNonce()).isEqualTo("nonce-1");
        verify(oAuthStateStore).save("state-1", "/problems", "hash-1");
    }

    @Test
    @DisplayName("로그인 시작: 이동 경로가 없으면 / 로 돌아온다")
    void startGitHubLoginDefaultsToRoot() {
        given(tokenGenerator.generate()).willReturn("state-1", "nonce-1");
        given(tokenGenerator.hash("nonce-1")).willReturn("hash-1");

        authService.startGitHubLogin(null);

        verify(oAuthStateStore).save("state-1", "/", "hash-1");
    }

    @Test
    @DisplayName("로그인 시작: 허용 목록 밖 이동 경로는 COMMON_INVALID_REQUEST")
    void startGitHubLoginRejectsExternalRedirect() {
        assertThatThrownBy(() -> authService.startGitHubLogin("https://evil.example"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
        verifyNoInteractions(oAuthStateStore);
    }

    @Test
    @DisplayName("콜백: state가 없거나 만료되면 GitHub를 호출하지 않고 AUTH_TOKEN_INVALID")
    void completeGitHubLoginRejectsUnknownState() {
        given(oAuthStateStore.consume("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.completeGitHubLogin("code", "unknown", null, "nonce-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        assertThatThrownBy(() -> authService.completeGitHubLogin("code", null, null, "nonce-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        verify(gitHubOAuthClient, never()).fetchUser(anyString());
    }

    @Test
    @DisplayName("콜백: 로그인을 시작한 브라우저가 아니면(login CSRF) AUTH_TOKEN_INVALID")
    void completeGitHubLoginRejectsOtherBrowser() {
        given(oAuthStateStore.consume("state-1")).willReturn(Optional.of(new OAuthState("/", "hash-1")));
        given(oAuthStateStore.consume("state-2")).willReturn(Optional.of(new OAuthState("/", "hash-1")));
        given(tokenGenerator.hash("victim-nonce")).willReturn("hash-victim");

        assertThatThrownBy(() -> authService.completeGitHubLogin("code", "state-1", null, "victim-nonce"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        assertThatThrownBy(() -> authService.completeGitHubLogin("code", "state-2", null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        verify(gitHubOAuthClient, never()).fetchUser(anyString());
    }

    @Test
    @DisplayName("콜백: 사용자가 GitHub 인가를 취소하면 state를 소비하고 AUTH_OAUTH_DENIED")
    void completeGitHubLoginHandlesCancel() {
        given(oAuthStateStore.consume("state-1")).willReturn(Optional.of(new OAuthState("/", "hash-1")));
        given(tokenGenerator.hash("nonce-1")).willReturn("hash-1");

        assertThatThrownBy(() -> authService.completeGitHubLogin(null, "state-1", "access_denied", "nonce-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_OAUTH_DENIED);
        verify(oAuthStateStore).consume("state-1");
        verify(gitHubOAuthClient, never()).fetchUser(anyString());
    }

    @Test
    @DisplayName("콜백: GitHub가 그 밖의 오류를 돌려주거나 인가 코드가 없으면 AUTH_OAUTH_FAILED")
    void completeGitHubLoginHandlesGitHubError() {
        given(oAuthStateStore.consume("state-1")).willReturn(Optional.of(new OAuthState("/", "hash-1")));
        given(oAuthStateStore.consume("state-2")).willReturn(Optional.of(new OAuthState("/", "hash-1")));
        given(tokenGenerator.hash("nonce-1")).willReturn("hash-1");

        assertThatThrownBy(() -> authService.completeGitHubLogin(null, "state-1", "server_error", "nonce-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_OAUTH_FAILED);
        assertThatThrownBy(() -> authService.completeGitHubLogin(null, "state-2", null, "nonce-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_OAUTH_FAILED);
    }

    @Test
    @DisplayName("콜백: 회원을 가입시키고 브라우저에 묶인 로그인 교환 코드를 붙여 프론트 콜백으로 보낸다")
    void completeGitHubLoginRedirectsWithLoginCode() {
        GitHubUser gitHubUser = new GitHubUser(1001L, "kim-dev", null, null);
        Member member = memberWithId(7L);
        given(oAuthStateStore.consume("state-1"))
            .willReturn(Optional.of(new OAuthState("/studies/12?tab=a&b=c", "hash-1")));
        given(tokenGenerator.hash("nonce-1")).willReturn("hash-1");
        given(gitHubOAuthClient.fetchUser("code")).willReturn(gitHubUser);
        given(memberService.findOrRegister(gitHubUser)).willReturn(new GitHubMemberRegistration(member, true));
        given(loginCodeStore.issue(7L, true, "hash-1")).willReturn("lc_abc");

        String redirectUrl = authService.completeGitHubLogin("code", "state-1", null, "nonce-1");

        assertThat(redirectUrl)
            .isEqualTo(
                AuthPropertiesFixture.FRONTEND_ORIGIN
                    + "/auth/callback?loginCode=lc_abc&redirectPath=/studies/12?tab%3Da%26b%3Dc"
            );
    }

    @Test
    @DisplayName("콜백 실패 주소: 오류 코드를 error로 붙인 프론트 콜백")
    void frontendCallbackErrorUrl() {
        assertThat(authService.frontendCallbackError(ErrorCode.AUTH_OAUTH_DENIED))
            .isEqualTo(AuthPropertiesFixture.FRONTEND_ORIGIN + "/auth/callback?error=AUTH_OAUTH_DENIED");
    }

    @Test
    @DisplayName("코드 교환: 코드가 없거나 이미 쓰였으면 AUTH_LOGIN_CODE_INVALID")
    void exchangeLoginCodeRejectsUsedCode() {
        given(loginCodeStore.consume("lc_used")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.exchangeLoginCode("lc_used", "nonce-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_LOGIN_CODE_INVALID);
        verifyNoInteractions(accessTokenProvider, refreshTokenStore);
    }

    @Test
    @DisplayName("코드 교환: 로그인을 시작한 브라우저가 아니면(login CSRF) AUTH_LOGIN_CODE_INVALID")
    void exchangeLoginCodeRejectsOtherBrowser() {
        given(loginCodeStore.consume("lc_abc")).willReturn(Optional.of(new LoginCodeClaim(7L, true, "hash-1")));
        given(loginCodeStore.consume("lc_def")).willReturn(Optional.of(new LoginCodeClaim(7L, true, "hash-1")));
        given(tokenGenerator.hash("victim-nonce")).willReturn("hash-victim");

        assertThatThrownBy(() -> authService.exchangeLoginCode("lc_abc", "victim-nonce"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_LOGIN_CODE_INVALID);
        assertThatThrownBy(() -> authService.exchangeLoginCode("lc_def", null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_LOGIN_CODE_INVALID);
        verifyNoInteractions(accessTokenProvider, refreshTokenStore);
    }

    @Test
    @DisplayName("코드 교환: Access Token과 Refresh Token을 발급하고 신규 가입 여부를 전달한다")
    void exchangeLoginCodeIssuesTokens() {
        Member member = memberWithId(7L);
        given(loginCodeStore.consume("lc_abc")).willReturn(Optional.of(new LoginCodeClaim(7L, true, "hash-1")));
        given(tokenGenerator.hash("nonce-1")).willReturn("hash-1");
        given(memberService.getMember(7L)).willReturn(member);
        given(accessTokenProvider.issue(member)).willReturn("access-token");
        given(accessTokenProvider.expiresInSeconds()).willReturn(3600L);
        given(refreshTokenStore.issue(7L)).willReturn("refresh-token");

        LoginResult result = authService.exchangeLoginCode("lc_abc", "nonce-1");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.expiresIn()).isEqualTo(3600L);
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.member()).isSameAs(member);
        assertThat(result.newMember()).isTrue();
    }

    private Member memberWithId(Long id) {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
