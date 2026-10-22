package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
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

/** 보안 설정이 프로필별 내부 경로 허용과 외부 API 권한 검사를 유지하는지 확인한다. */
class SecurityConfigurationTest {
    private final WebApplicationContextRunner runner
        = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SecurityTestConfiguration.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @ParameterizedTest
    @DisplayName("모든 프로필에서 내부 요청을 허용하고 외부 API 권한을 검사한다")
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

    /** 보안 필터 체인과 프로브 컨트롤러를 테스트에 등록한다. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import(
        {
            SecurityConfiguration.class,
            ProbeController.class
        }
    )
    static class SecurityTestConfiguration {}

    /** 내부·외부 경로의 보안 동작을 확인할 응답을 제공한다. */
    @TestComponent
    @RestController
    static class ProbeController {
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

        @PostMapping("/internal/v1/probe")
        String write() {
            return "ok";
        }
    }
}
