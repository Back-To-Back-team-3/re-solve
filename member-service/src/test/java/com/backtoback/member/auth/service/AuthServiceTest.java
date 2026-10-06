package com.backtoback.member.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.member.auth.AuthPropertiesFixture;
import com.backtoback.member.auth.client.GitHubOAuthClient;
import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.store.LoginCodeStore;
import com.backtoback.member.auth.store.LoginCodeStore.LoginCodeClaim;
import com.backtoback.member.auth.store.OAuthStateStore;
import com.backtoback.member.auth.store.RefreshTokenStore;
import com.backtoback.member.auth.token.AccessTokenProvider;
import com.backtoback.member.auth.token.SecureTokenGenerator;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.service.GitHubMemberRegistration;
import com.backtoback.member.member.service.MemberService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
                gitHubOAuthClient,
                accessTokenProvider,
                memberService
            );
    }

    @Test
    @DisplayName("로그인 시작: state를 이동 경로와 함께 저장하고 GitHub 인가 주소를 돌려준다")
    void startGitHubLoginStoresState() {
        given(tokenGenerator.generate()).willReturn("state-1");
        given(gitHubOAuthClient.buildAuthorizeUrl("state-1")).willReturn("https://github.com/login/oauth/authorize?x");

        String authorizeUrl = authService.startGitHubLogin("/problems");

        assertThat(authorizeUrl).isEqualTo("https://github.com/login/oauth/authorize?x");
        verify(oAuthStateStore).save("state-1", "/problems");
    }

    @Test
    @DisplayName("로그인 시작: 이동 경로가 없으면 / 로 돌아온다")
    void startGitHubLoginDefaultsToRoot() {
        given(tokenGenerator.generate()).willReturn("state-1");

        authService.startGitHubLogin(null);

        verify(oAuthStateStore).save("state-1", "/");
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

        assertThatThrownBy(() -> authService.completeGitHubLogin("code", "unknown"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID);
        verify(gitHubOAuthClient, never()).fetchUser(anyString());
    }

    @Test
    @DisplayName("콜백: 회원을 가입시키고 로그인 교환 코드를 붙여 프론트 콜백으로 보낸다")
    void completeGitHubLoginRedirectsWithLoginCode() {
        GitHubUser gitHubUser = new GitHubUser(1001L, "kim-dev", null, null);
        Member member = memberWithId(7L);
        given(oAuthStateStore.consume("state-1")).willReturn(Optional.of("/studies/12?tab=a&b=c"));
        given(gitHubOAuthClient.fetchUser("code")).willReturn(gitHubUser);
        given(memberService.findOrRegister(gitHubUser)).willReturn(new GitHubMemberRegistration(member, true));
        given(loginCodeStore.issue(7L, true)).willReturn("lc_abc");

        String redirectUrl = authService.completeGitHubLogin("code", "state-1");

        assertThat(redirectUrl)
            .isEqualTo(
                AuthPropertiesFixture.FRONTEND_ORIGIN
                    + "/auth/callback?loginCode=lc_abc&redirectPath=/studies/12?tab%3Da%26b%3Dc"
            );
    }

    @Test
    @DisplayName("코드 교환: 코드가 없거나 이미 쓰였으면 AUTH_LOGIN_CODE_INVALID")
    void exchangeLoginCodeRejectsUsedCode() {
        given(loginCodeStore.consume("lc_used")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.exchangeLoginCode("lc_used"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_LOGIN_CODE_INVALID);
        verifyNoInteractions(accessTokenProvider, refreshTokenStore);
    }

    @Test
    @DisplayName("코드 교환: Access Token과 Refresh Token을 발급하고 신규 가입 여부를 전달한다")
    void exchangeLoginCodeIssuesTokens() {
        Member member = memberWithId(7L);
        given(loginCodeStore.consume("lc_abc")).willReturn(Optional.of(new LoginCodeClaim(7L, true)));
        given(memberService.getMember(7L)).willReturn(member);
        given(accessTokenProvider.issue(member)).willReturn("access-token");
        given(accessTokenProvider.expiresInSeconds()).willReturn(3600L);
        given(refreshTokenStore.issue(7L)).willReturn("refresh-token");

        LoginResult result = authService.exchangeLoginCode("lc_abc");

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
