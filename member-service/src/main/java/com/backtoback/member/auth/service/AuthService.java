package com.backtoback.member.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

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

import lombok.RequiredArgsConstructor;

/**
 * GitHub OAuth2 가입·로그인 (API 명세서 1.1.1 ~ 1.1.3).
 * <ol>
 * <li>로그인 시작: state를 Redis에 저장하고 GitHub 인가 화면 주소를 만든다.</li>
 * <li>콜백: state를 1회 소비하고, GitHub 사용자로 회원을 찾거나 가입시킨 뒤 1회용 로그인 교환 코드를 발급한다.</li>
 * <li>코드 교환: 로그인 교환 코드를 1회 소비하고 Access Token과 Refresh Token을 발급한다.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthProperties authProperties;
    private final RedirectPathPolicy redirectPathPolicy;
    private final SecureTokenGenerator tokenGenerator;
    private final OAuthStateStore oAuthStateStore;
    private final LoginCodeStore loginCodeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final GitHubOAuthClient gitHubOAuthClient;
    private final AccessTokenProvider accessTokenProvider;
    private final MemberService memberService;

    public String startGitHubLogin(String redirectPath) {
        String path = redirectPath == null ? RedirectPathPolicy.DEFAULT_PATH : redirectPath;
        if (!redirectPathPolicy.isAllowed(path)) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_REQUEST);
        }
        String state = tokenGenerator.generate();
        oAuthStateStore.save(state, path);
        return gitHubOAuthClient.buildAuthorizeUrl(state);
    }

    /**
     * @return 로그인 교환 코드를 붙인 프론트 콜백 주소
     */
    public String completeGitHubLogin(String authorizationCode, String state) {
        String redirectPath
            = oAuthStateStore.consume(state).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));

        GitHubUser gitHubUser = gitHubOAuthClient.fetchUser(authorizationCode);
        GitHubMemberRegistration registration = memberService.findOrRegister(gitHubUser);
        String loginCode = loginCodeStore.issue(registration.member().getId(), registration.newMember());

        return UriComponentsBuilder
            .fromUriString(authProperties.frontendOrigin())
            .path("/auth/callback")
            .queryParam("loginCode", loginCode)
            .queryParam("redirectPath", redirectPath)
            .encode()
            .build()
            .toUriString();
    }

    public LoginResult exchangeLoginCode(String loginCode) {
        LoginCodeClaim claim
            = loginCodeStore
                .consume(loginCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_LOGIN_CODE_INVALID));
        Member member = memberService.getMember(claim.memberId());
        if (member.isWithdrawn()) {
            throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
        }

        String accessToken = accessTokenProvider.issue(member);
        String refreshToken = refreshTokenStore.issue(member.getId());
        return new LoginResult(
            accessToken,
            accessTokenProvider.expiresInSeconds(),
            refreshToken,
            member,
            claim.newMember()
        );
    }
}
