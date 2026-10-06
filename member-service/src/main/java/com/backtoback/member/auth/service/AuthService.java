package com.backtoback.member.auth.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.backtoback.member.auth.client.GitHubOAuthClient;
import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.auth.store.AccessTokenBlocklist;
import com.backtoback.member.auth.store.LoginCodeStore;
import com.backtoback.member.auth.store.LoginCodeStore.LoginCodeClaim;
import com.backtoback.member.auth.store.OAuthStateStore;
import com.backtoback.member.auth.store.RefreshTokenStore;
import com.backtoback.member.auth.store.RefreshTokenStore.RotationResult;
import com.backtoback.member.auth.token.AccessTokenProvider;
import com.backtoback.member.auth.token.SecureTokenGenerator;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.service.GitHubMemberRegistration;
import com.backtoback.member.member.service.MemberService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * GitHub OAuth2 가입·로그인과 토큰 관리 (API 명세서 1.1.1 ~ 1.1.5).
 * <ol>
 * <li>로그인 시작: state를 Redis에 저장하고 GitHub 인가 화면 주소를 만든다.</li>
 * <li>콜백: state를 1회 소비하고, GitHub 사용자로 회원을 찾거나 가입시킨 뒤 1회용 로그인 교환 코드를 발급한다.</li>
 * <li>코드 교환: 로그인 교환 코드를 1회 소비하고 Access Token과 Refresh Token을 발급한다.</li>
 * <li>재발급: Refresh Token을 교체하고 Access Token을 새로 발급한다.</li>
 * <li>로그아웃: Refresh Token을 폐기하고 Access Token을 남은 수명 동안 차단한다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties authProperties;
    private final RedirectPathPolicy redirectPathPolicy;
    private final SecureTokenGenerator tokenGenerator;
    private final OAuthStateStore oAuthStateStore;
    private final LoginCodeStore loginCodeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenBlocklist accessTokenBlocklist;
    private final GitHubOAuthClient gitHubOAuthClient;
    private final AccessTokenProvider accessTokenProvider;
    private final MemberService memberService;
    private final Clock clock;

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

    /**
     * Refresh Token을 교체하고 Access Token을 새로 발급한다.
     * 방금 교체된 토큰(유예 시간 안)으로 온 요청은 다른 탭·동시 요청으로 보고 Access Token만 발급한다.
     */
    public TokenRefreshResult refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        RotationResult rotation = refreshTokenStore.rotate(refreshToken);
        switch (rotation.outcome()) {
            case INVALID -> throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
            case REUSED -> {
                log.warn("Refresh token reuse detected. All refresh tokens revoked. memberId={}", rotation.memberId());
                throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_REUSED);
            }
            default -> {
                // ROTATED, GRACE
            }
        }

        Member member
            = memberService.findMember(rotation.memberId()).filter(found -> !found.isWithdrawn()).orElse(null);
        if (member == null) {
            if (rotation.newRefreshToken() != null) {
                refreshTokenStore.revoke(rotation.memberId(), rotation.newRefreshToken());
            }
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        String accessToken = accessTokenProvider.issue(member);
        return new TokenRefreshResult(accessToken, accessTokenProvider.expiresInSeconds(), rotation.newRefreshToken());
    }

    /**
     * Refresh Token을 폐기하고, Access Token은 남은 수명 동안 차단 목록에 올린다.
     *
     * @param memberId Gateway가 전달한 요청자 ID ({@code X-User-Id})
     * @param authorization {@code Authorization} 헤더 값
     * @param refreshToken Refresh Token 쿠키 값. 없으면 Access Token만 차단한다.
     */
    public void logout(Long memberId, String authorization, String refreshToken) {
        String accessToken = extractBearerToken(authorization);
        Jwt jwt
            = accessTokenProvider
                .decode(accessToken)
                .filter(decoded -> String.valueOf(memberId).equals(decoded.getSubject()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));

        accessTokenBlocklist.block(jwt.getId(), Duration.between(clock.instant(), jwt.getExpiresAt()));
        if (StringUtils.hasText(refreshToken)) {
            refreshTokenStore.revoke(memberId, refreshToken);
        }
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
