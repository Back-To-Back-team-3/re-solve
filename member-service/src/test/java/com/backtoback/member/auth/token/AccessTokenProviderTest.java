package com.backtoback.member.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.member.auth.AuthPropertiesFixture;
import com.backtoback.member.member.domain.Member;

class AccessTokenProviderTest {

    @Test
    @DisplayName("회원 ID를 sub, 권한을 role 클레임에 담고 1시간 뒤 만료되는 HS256 토큰을 발급한다")
    void issuesSignedAccessToken() {
        Instant issuedAt = Instant.now();
        Clock clock = Clock.fixed(issuedAt, ZoneOffset.UTC);
        AccessTokenProvider provider = new AccessTokenProvider(AuthPropertiesFixture.create(), clock);
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", 7L);

        String token = provider.issue(member);

        SecretKeySpec key
            = new SecretKeySpec(AuthPropertiesFixture.JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).build().decode(token);
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString(AccessTokenProvider.ROLE_CLAIM)).isEqualTo("USER");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(3600));
        assertThat(provider.expiresInSeconds()).isEqualTo(3600L);
    }
}
