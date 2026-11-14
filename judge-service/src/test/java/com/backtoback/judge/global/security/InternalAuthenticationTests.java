package com.backtoback.judge.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InternalAuthenticationTests {
    private final WebApplicationContextRunner runner
        = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SecurityTestConfiguration.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    /**
     * 각 프로필에서 내부 API 허용과 외부 API 권한 검사를 함께 검증한다.
     *
     * @param profile 검증할 활성 프로필 또는 프로필 조합
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "local",
            "dev",
            "prod",
            "staging",
            "default",
            "local,prod",
            "prod,local",
            "staging,dev",
            "dev,staging"
        }
    )
    void allProfilesAllowInternalRequestsAndPreserveExternalApiAuthorization(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/internal/v1/probe")).andExpect(status().isOk());
            mvc
                .perform(post("/internal/v1/probe").header("Authorization", "Bearer not-verified"))
                .andExpect(status().isOk());

            mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
            mvc
                .perform(get("/api/v1/admin/probe").header("X-User-Id", "12").header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
            mvc
                .perform(get("/api/v1/admin/probe").header("X-User-Id", "12").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import(
        {
            SecurityConfiguration.class,
            ProbeController.class
        }
    )
    static class SecurityTestConfiguration {
        // 테스트 컨텍스트에서만 내부 인증 경계를 구성한다.
    }

    @TestComponent
    @RestController
    static class ProbeController {
        /**
         * 보안 경계 검증용 요청에 정상 응답을 반환한다.
         *
         * @return 테스트용 정상 응답 문자열
         */
        @GetMapping(
            {
                "/internal/v1/probe",
                "/api/v1/probe",
                "/api/v1/admin/probe"
            }
        )
        String read() {
            return "ok";
        }

        /**
         * 보안 경계 검증용 요청에 정상 응답을 반환한다.
         *
         * @return 테스트용 정상 응답 문자열
         */
        @PostMapping("/internal/v1/probe")
        String write() {
            return "ok";
        }
    }
}
