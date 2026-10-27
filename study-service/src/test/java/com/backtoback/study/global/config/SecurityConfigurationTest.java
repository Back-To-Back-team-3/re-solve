package com.backtoback.study.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;
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

    // Gateway가 내려주는 X-User-Id는 숫자형 회원 ID 문자열이다. StudyController가 상세·내 스터디 조회에서
    // Long.parseLong(user.userId())로 파싱하므로 숫자가 아닌 값을 쓰면 500이 난다.
    private static final String USER_ID = "1";

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

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Boot 기본 사용자 계정을 만들지 않는다")
    void doesNotCreateDefaultUser() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("스터디 목록·상세 조회는 로그인 없이 허용한다")
    void permitsPublicStudyReads() throws Exception {
        mockMvc.perform(get("/api/v1/studies")).andExpect(status().isOk());
        // 테스트 DB에 study id=1이 없어 404지만, 401이 아니라는 것이 permitAll 적용을 증명한다.
        // @AuthenticationPrincipal 분기(로그인 시 memberId 전달)는 StudyControllerTest(mock)에서 검증한다.
        mockMvc.perform(get("/api/v1/studies/1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공개 조회에 인증 헤더가 있어도 permitAll이 유지된다")
    void readsUserOnPublicStudyRead() throws Exception {
        mockMvc
            .perform(get("/api/v1/studies/1").header("X-User-Id", USER_ID).header("X-User-Role", "USER"))
            .andExpect(status().isNotFound());
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

    @Test
    @DisplayName("local이 아닌 프로필에서는 Swagger 경로에 익명으로 요청하면 401을 반환한다")
    void rejectsSwaggerOutsideLocalProfile() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProbeController.class)
    static class ProbeConfiguration {

    }

    /**
     * 보안 체인의 경로별 권한만 확인하도록 각 경로에 고정 응답을 돌려준다.
     */
    @RestController
    static class ProbeController {

        // GET /api/v1/studies, GET /api/v1/studies/{studyId}는 StudyController(discovery)가 실제로
        // 구현해 더 이상 여기서 가짜 응답을 두지 않는다.

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
