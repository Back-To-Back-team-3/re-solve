package com.backtoback.study.hint.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.exception.HintInvalidStatusTransitionException;
import com.backtoback.study.hint.service.HintCommandService;

/**
 * 힌트 내부 API 요청 바인딩·응답 변환을 검증한다 (api-spec §6.2.1).
 *
 * @WebMvcTest는 JpaAuditingConfiguration으로 인한 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 §6.7)
 * @SpringBootTest + Testcontainers를 사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class HintInternalControllerTest {

    @Container
    @SuppressWarnings("rawtypes")
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
    private HintCommandService hintCommandService;

    @Test
    @DisplayName("6.2.1 REQUESTED 상태이면 200을 반환한다")
    void transitionToGenerating_requested_returns200() throws Exception {
        willDoNothing().given(hintCommandService).transitionToGenerating(eq(42L));

        mockMvc
            .perform(patch("/internal/v1/hint-requests/42/generating"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("6.2.1 REQUESTED가 아닌 상태이면 409를 반환한다")
    void transitionToGenerating_invalidStatus_returns409() throws Exception {
        willThrow(new HintInvalidStatusTransitionException()).given(hintCommandService).transitionToGenerating(eq(99L));

        mockMvc
            .perform(patch("/internal/v1/hint-requests/99/generating"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value(StudyErrorCode.HINT_INVALID_STATUS_TRANSITION.getCode()));
    }

    @Test
    @DisplayName("6.2.1 존재하지 않는 hintRequestId이면 404를 반환한다")
    void transitionToGenerating_notFound_returns404() throws Exception {
        willThrow(new StudyException(StudyErrorCode.HINT_REQUEST_NOT_FOUND))
            .given(hintCommandService)
            .transitionToGenerating(eq(999L));

        mockMvc
            .perform(patch("/internal/v1/hint-requests/999/generating"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value(StudyErrorCode.HINT_REQUEST_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("6.2.1 hintRequestId가 숫자가 아니면 400을 반환한다")
    void transitionToGenerating_invalidId_returns400() throws Exception {
        mockMvc.perform(patch("/internal/v1/hint-requests/abc/generating")).andExpect(status().isBadRequest());
    }
}
