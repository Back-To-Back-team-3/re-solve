package com.backtoback.member.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.backtoback.member.auth.config.AuthProperties;
import com.backtoback.member.member.domain.Member;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Access Token(JWT, HS256)을 발급한다. 검증은 같은 서명 키를 가진 API Gateway가 한다.
 * {@code jti}는 로그아웃 시 남은 수명 동안 차단 목록에 올릴 때 쓴다.
 */
@Component
public class AccessTokenProvider {

    public static final String ROLE_CLAIM = "role";
    static final String ISSUER = "re-solve";

    private final JwtEncoder jwtEncoder;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public AccessTokenProvider(AuthProperties authProperties, Clock clock) {
        byte[] secret = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        SecretKey secretKey = new SecretKeySpec(secret, "HmacSHA256");
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        this.accessTokenTtl = authProperties.jwt().accessTokenTtl();
        this.clock = clock;
    }

    public String issue(Member member) {
        Instant now = clock.instant();
        JwtClaimsSet claims
            = JwtClaimsSet
                .builder()
                .issuer(ISSUER)
                .subject(String.valueOf(member.getId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .claim(ROLE_CLAIM, member.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long expiresInSeconds() {
        return accessTokenTtl.toSeconds();
    }
}
