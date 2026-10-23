package com.backtoback.study.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.web.security.user.GatewayUser;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecurityConfigurationTest {

    private static final String USER_ID = "member-1";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("스터디 목록·상세 조회는 로그인 없이 허용한다")
    void permitsPublicStudyReads() throws Exception {
        mockMvc.perform(get("/api/v1/studies")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/studies/1")).andExpect(content().string("anonymous"));
    }

    @Test
    @DisplayName("공개 조회에 인증 헤더가 있으면 사용자 정보를 읽는다")
    void readsUserOnPublicStudyRead() throws Exception {
        mockMvc
            .perform(get("/api/v1/studies/1").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(content().string(USER_ID));
    }

    @Test
    @DisplayName("로그인이 필요한 스터디 경로에 익명으로 요청하면 401을 반환한다")
    void rejectsAnonymousOnLoginRequiredPaths() throws Exception {
        mockMvc.perform(get("/api/v1/studies/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/studies/recommendations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/studies/1/memberships")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인이 필요한 스터디 경로에 인증 헤더가 있으면 허용한다")
    void permitsUserOnLoginRequiredPaths() throws Exception {
        mockMvc
            .perform(get("/api/v1/studies/me").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(content().string(USER_ID));
    }

    @Test
    @DisplayName("관리자 경로는 USER에게 403, ADMIN에게 허용한다")
    void restrictsAdminPathsToAdmin() throws Exception {
        mockMvc
            .perform(get("/api/v1/admin/studies/probe").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(get("/api/v1/admin/studies/probe").header("X-User-Id", USER_ID).header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("내부 경로는 인증 없이 허용한다")
    void permitsInternalPaths() throws Exception {
        mockMvc.perform(get("/internal/v1/studies/1/members")).andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProbeController.class)
    static class ProbeConfiguration {

    }

    /**
     * 아직 study 기능 Controller가 없어 경로별 권한만 확인하는 테스트용 Controller.
     */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/studies")
        String studies() {
            return "studies";
        }

        @GetMapping("/api/v1/studies/{studyId}")
        String study(@AuthenticationPrincipal GatewayUser user) {
            return user == null ? "anonymous" : user.userId();
        }

        @GetMapping("/api/v1/studies/me")
        String myStudies(@AuthenticationPrincipal GatewayUser user) {
            return user.userId();
        }

        @GetMapping("/api/v1/studies/recommendations")
        String recommendations() {
            return "recommendations";
        }

        @GetMapping("/api/v1/studies/{studyId}/memberships")
        String memberships() {
            return "memberships";
        }

        @GetMapping("/api/v1/admin/studies/probe")
        String admin() {
            return "admin";
        }

        @GetMapping("/internal/v1/studies/{studyId}/members")
        String internalMembers() {
            return "members";
        }
    }
}
