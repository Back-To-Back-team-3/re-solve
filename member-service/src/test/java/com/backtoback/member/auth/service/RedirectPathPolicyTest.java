package com.backtoback.member.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.backtoback.member.auth.AuthPropertiesFixture;
import com.backtoback.member.auth.config.AuthProperties;

class RedirectPathPolicyTest {

    private final RedirectPathPolicy policy = new RedirectPathPolicy(AuthPropertiesFixture.create());

    @ParameterizedTest
    @ValueSource(
        strings = {
            "/",
            "/problems",
            "/studies/12?tab=assignments"
        }
    )
    @DisplayName("같은 출처의 상대 경로는 허용한다")
    void allowsRelativePaths(String path) {
        assertThat(policy.isAllowed(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "problems",
            "//evil.example",
            "https://evil.example",
            "/\\evil.example",
            "/../admin",
            "/a\nb"
        }
    )
    @DisplayName("절대 주소·프로토콜 상대 주소·경로 이동·제어 문자는 거부한다")
    void rejectsUnsafePaths(String path) {
        assertThat(policy.isAllowed(path)).isFalse();
    }

    @Test
    @DisplayName("허용 목록이 좁혀지면 목록 밖 경로를 거부한다")
    void rejectsPathsOutsideAllowList() {
        AuthProperties base = AuthPropertiesFixture.create();
        AuthProperties narrowed
            = new AuthProperties(
                base.frontendOrigin(),
                List.of("/problems"),
                base.github(),
                base.jwt(),
                new AuthProperties.RefreshToken(Duration.ofDays(14), Duration.ofSeconds(10), true)
            );
        RedirectPathPolicy narrowPolicy = new RedirectPathPolicy(narrowed);

        assertThat(narrowPolicy.isAllowed("/problems")).isTrue();
        assertThat(narrowPolicy.isAllowed("/problems/3")).isTrue();
        assertThat(narrowPolicy.isAllowed("/problemset")).isFalse();
        assertThat(narrowPolicy.isAllowed("/studies")).isFalse();
    }
}
