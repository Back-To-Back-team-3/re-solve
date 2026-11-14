package com.backtoback.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class AuthPropertiesTest {

    @Mock
    private Environment environment;

    @Test
    @DisplayName("bypass-enabled가 false이면 어떤 프로필에서도 예외가 발생하지 않는다")
    void allowsWhenBypassDisabled() {
        AuthProperties properties = new AuthProperties(environment);
        properties.setBypassEnabled(false);

        assertThatCode(properties::validateSecurityPolicy).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local 또는 dev 프로필에서는 bypass-enabled가 true여도 허용된다")
    void allowsWhenBypassEnabledInLocal() {
        given(environment.getActiveProfiles())
            .willReturn(
                new String[] {
                    "local",
                    "dev"
                }
            );

        AuthProperties properties = new AuthProperties(environment);
        properties.setBypassEnabled(true);

        assertThatCode(properties::validateSecurityPolicy).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 프로필에서 bypass-enabled가 true이면 애플리케이션 시작을 차단한다")
    void throwsExceptionWhenBypassEnabledInProd() {
        given(environment.getActiveProfiles())
            .willReturn(
                new String[] {
                    "prod"
                }
            );

        AuthProperties properties = new AuthProperties(environment);
        properties.setBypassEnabled(true);

        assertThatThrownBy(properties::validateSecurityPolicy)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("prod 또는 staging");
    }

    @Test
    @DisplayName("staging 프로필에서 bypass-enabled가 true이면 애플리케이션 시작을 차단한다")
    void throwsExceptionWhenBypassEnabledInStaging() {
        given(environment.getActiveProfiles())
            .willReturn(
                new String[] {
                    "staging"
                }
            );

        AuthProperties properties = new AuthProperties(environment);
        properties.setBypassEnabled(true);

        assertThatThrownBy(properties::validateSecurityPolicy)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("prod 또는 staging");
    }
}
