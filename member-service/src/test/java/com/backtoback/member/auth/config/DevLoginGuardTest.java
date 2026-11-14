package com.backtoback.member.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.backtoback.member.auth.AuthPropertiesFixture;

class DevLoginGuardTest {

    @Test
    @DisplayName("prod·staging 프로필에서 개발용 로그인이 켜져 있으면 기동을 실패시킨다")
    void rejectsDevLoginInProduction() {
        for (String profile : new String[] {
            "prod",
            "staging"
        }) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);

            assertThatThrownBy(() -> new DevLoginGuard(devLogin(true), environment))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("local 프로필에서 켜거나 prod 프로필에서 꺼져 있으면 기동한다")
    void allowsDevLoginOutsideProduction() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatCode(() -> new DevLoginGuard(devLogin(true), local)).doesNotThrowAnyException();
        assertThatCode(() -> new DevLoginGuard(devLogin(false), prod)).doesNotThrowAnyException();
    }

    private AuthProperties devLogin(boolean enabled) {
        AuthProperties base = AuthPropertiesFixture.create();
        return new AuthProperties(
            base.frontendOrigin(),
            base.allowedRedirectPaths(),
            base.github(),
            base.jwt(),
            base.refreshToken(),
            enabled
        );
    }
}
