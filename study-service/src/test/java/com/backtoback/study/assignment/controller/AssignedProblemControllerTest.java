package com.backtoback.study.assignment.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.assignment.service.AssignedProblemQueryService;

/**
 * 5.4.5 배정 문제 레벨 잠금 예외 확인 API의 인증·요청 바인딩·응답 형식을 검증한다.
 * {@code @WebMvcTest}는 {@code JpaAuditingConfiguration}으로 인한 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7)
 * 보안 필터까지 함께 확인할 수 있는 {@code @SpringBootTest} + Testcontainers를 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AssignedProblemControllerTest {

    private static final String URL = "/api/v1/studies/me/assigned-problems/{problemId}";
    private static final String USER_ID = "7";
    private static final String USER_ROLE = "USER";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssignedProblemQueryService assignedProblemQueryService;

    @Test
    @DisplayName("5.4.5 로그인 회원에게 배정된 문제이면 200과 ApiResponse로 감싼 assigned=true를 반환한다")
    void getAssignedProblem_assigned_returns200WithApiResponse() throws Exception {
        // given
        given(assignedProblemQueryService.isAssigned(7L, 101L)).willReturn(true);

        // when & then
        mockMvc
            .perform(get(URL, "101").header("X-User-Id", USER_ID).header("X-User-Role", USER_ROLE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.problemId").value("101"))
            .andExpect(jsonPath("$.data.assigned").value(true));
    }

    @Test
    @DisplayName("5.4.5 배정 문제가 아니거나 없는 문제이면 200과 assigned=false를 반환한다")
    void getAssignedProblem_notAssigned_returns200WithFalse() throws Exception {
        // given
        given(assignedProblemQueryService.isAssigned(7L, 999L)).willReturn(false);

        // when & then
        mockMvc
            .perform(get(URL, "999").header("X-User-Id", USER_ID).header("X-User-Role", USER_ROLE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.problemId").value("999"))
            .andExpect(jsonPath("$.data.assigned").value(false));
    }

    @Test
    @DisplayName("5.4.5 problemId가 숫자가 아니면 판정하지 않고 400 COMMON_INVALID_REQUEST를 반환한다")
    void getAssignedProblem_nonNumericProblemId_returns400() throws Exception {
        // when & then
        mockMvc
            .perform(get(URL, "abc").header("X-User-Id", USER_ID).header("X-User-Role", USER_ROLE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verifyNoInteractions(assignedProblemQueryService);
    }

    @Test
    @DisplayName("5.4.5 인증 헤더 없이 요청하면 판정하지 않고 401을 반환한다")
    void getAssignedProblem_anonymous_returns401() throws Exception {
        // when & then
        mockMvc.perform(get(URL, "101")).andExpect(status().isUnauthorized());
        verifyNoInteractions(assignedProblemQueryService);
    }
}
