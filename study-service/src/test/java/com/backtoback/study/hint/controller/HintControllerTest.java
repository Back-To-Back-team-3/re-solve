package com.backtoback.study.hint.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.domain.HintRequestStatus;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.hint.dto.HintDetailResponse;
import com.backtoback.study.hint.dto.HintHistoryResponse;
import com.backtoback.study.hint.service.HintDailyLimitService;
import com.backtoback.study.hint.service.HintQueryService;
import com.backtoback.study.hint.service.HintRequestService;

/**
 * 힌트 요청 API 요청 바인딩·응답 변환을 검증한다(API 명세서 §6.1.1~§6.1.3).
 *
 * @WebMvcTest는 JpaAuditingConfiguration으로 인한 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 §6.7)
 * @SpringBootTest + Testcontainers를 사용한다.
 */
@SpringBootTest(properties = "study.hint.feature.enabled=true")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class HintControllerTest {

    private static final String USER_ID = "7";
    private static final String USER_ROLE = "USER";

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
    private HintRequestService hintRequestService;

    @MockitoBean
    private HintQueryService hintQueryService;

    @Test
    @DisplayName("6.1.1 정상 요청이면 202와 힌트 요청 결과를 반환한다")
    void requestHint_validRequest_returns202() throws Exception {
        given(hintRequestService.request(eq(7L), eq(100L), eq(1)))
            .willReturn(new HintAcceptResult(42L, HintRequestStatus.REQUESTED, null, 3));

        mockMvc
            .perform(
                post("/api/v1/hint-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
                    .content("""
                        {
                            "problemId": "100",
                            "level": 1
                        }""")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.hintRequestId").value("42"))
            .andExpect(jsonPath("$.data.status").value("REQUESTED"))
            .andExpect(jsonPath("$.data.content").doesNotExist())
            .andExpect(jsonPath("$.data.todayUsedCount").value(3))
            .andExpect(jsonPath("$.data.dailyLimit").value(20));
    }

    @Test
    @DisplayName("6.1.1 캐시 히트이면 202와 content를 함께 반환한다")
    void requestHint_cacheHit_returnContentInResponse() throws Exception {
        given(hintRequestService.request(eq(7L), eq(100L), eq(1)))
            .willReturn(new HintAcceptResult(43L, HintRequestStatus.READY, "힌트 내용입니다", 5));

        mockMvc
            .perform(
                post("/api/v1/hint-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
                    .content("""
                        {
                            "problemId": "100",
                            "level": 1
                        }""")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.content").value("힌트 내용입니다"));
    }

    @Test
    @DisplayName("6.1.1 problemId가 없으면 400을 반환한다")
    void requestHint_missingProblemId_returns400() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/hint-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
                    .content("""
                        {
                            "level": 1
                        }""")
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6.1.1 level이 0이면 400을 반환한다")
    void requestHint_levelZero_returns400() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/hint-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
                    .content("""
                        {
                            "problemId": "100",
                            "level": 0
                        }""")
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6.1.1 level이 4이면 400을 반환한다")
    void requestHint_levelFour_returns400() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/hint-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
                    .content("""
                        {
                            "problemId": "100",
                            "level": 4
                        }""")
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6.1.2 정상 요청이면 200과 힌트 이력을 반환한다")
    void getHintHistory_validRequest_returns200() throws Exception {
        HintHistoryResponse response
            = new HintHistoryResponse("100", List.of(), 1, 0, HintDailyLimitService.DAILY_LIMIT);
        given(hintQueryService.getHistory(eq(7L), eq(100L))).willReturn(response);

        mockMvc
            .perform(
                get("/api/v1/hint-requests")
                    .param("problemId", "100")
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.problemId").value("100"))
            .andExpect(jsonPath("$.data.hints").isArray())
            .andExpect(jsonPath("$.data.nextAvailableLevel").value(1))
            .andExpect(jsonPath("$.data.todayUsedCount").value(0))
            .andExpect(jsonPath("$.data.dailyLimit").value(20));
    }

    @Test
    @DisplayName("6.1.2 problemId가 숫자가 아니면 400을 반환한다")
    void getHintHistory_invalidProblemId_returns400() throws Exception {
        mockMvc
            .perform(
                get("/api/v1/hint-requests")
                    .param("problemId", "abc")
                    .header("X-User-Id", USER_ID)
                    .header("X-User-Role", USER_ROLE)
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6.1.3 정상 요청이면 200과 힌트 단건을 반환한다")
    void getHintDetail_validRequest_returns200() throws Exception {
        HintDetailResponse response
            = new HintDetailResponse("42", "100", 1, HintRequestStatus.READY, false, null, "힌트 내용", null);
        given(hintQueryService.getDetail(eq(7L), eq(42L))).willReturn(response);

        mockMvc
            .perform(get("/api/v1/hint-requests/42").header("X-User-Id", USER_ID).header("X-User-Role", USER_ROLE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hintRequestId").value("42"))
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.content").value("힌트 내용"));
    }

    @Test
    @DisplayName("6.1.3 존재하지 않는 hintRequestId이면 404를 반환한다")
    void getHintDetail_notFound_returns404() throws Exception {
        given(hintQueryService.getDetail(eq(7L), eq(99L)))
            .willThrow(new StudyException(StudyErrorCode.HINT_REQUEST_NOT_FOUND));

        mockMvc
            .perform(get("/api/v1/hint-requests/99").header("X-User-Id", USER_ID).header("X-User-Role", USER_ROLE))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("6.1.3 hintRequestId가 숫자가 아니면 400을 반환한다")
    void getHintDetail_invalidId_returns400() throws Exception {
        mockMvc
            .perform(get("/api/v1/hint-requests/abc").header("X-User-Id", USER_ID).header("X-User-Role", USER_ROLE))
            .andExpect(status().isBadRequest());
    }
}
