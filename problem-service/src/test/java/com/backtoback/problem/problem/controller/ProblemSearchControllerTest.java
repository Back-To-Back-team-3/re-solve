package com.backtoback.problem.problem.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemReadStore;

/** 문제 검색의 인증·요청 검증과 시각 응답을 검증한다. */
@WebMvcTest(ProblemSearchController.class)
@Import(
    {
        SecurityConfiguration.class,
        ProblemCreateBodyReader.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class ProblemSearchControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemReadStore store;

    /**
     * 고정 회차 등록 시각을 서울 오프셋과 함께 반환한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("로그인 회원의 검색 응답은 회차 등록 시각에 서울 오프셋을 포함한다")
    void seoulTimestamp() throws Exception {
        var revision = mock(ProblemRevision.class);
        given(revision.getId()).willReturn(2L);
        given(revision.getRevisionNumber()).willReturn(1);
        given(revision.getTitle()).willReturn("문제");
        given(revision.getCreatedAt()).willReturn(LocalDateTime.of(2026, 11, 7, 10, 20, 30));
        var item = new ProblemReadStore.Item(1L, null, revision, true, false, List.of(), List.of("java"));
        given(store.search(eq(10L), any())).willReturn(new PageImpl<>(List.of(item)));
        mvc
            .perform(get("/api/v1/problems").header("X-User-Id", "10").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].publishedAt").value("2026-11-07T10:20:30+09:00"));
    }

    /**
     * 단일 필드 제약을 위반한 검색 요청은 저장소를 호출하지 않는다.
     *
     * @param field 요청 필드
     * @param value 잘못된 값
     * @throws Exception MVC 요청 실패
     */
    @ParameterizedTest
    @CsvSource(
        {
            "page,-1",
            "size,0",
            "size,101",
            "solvedStatus,UNKNOWN",
            "difficulty,4"
        }
    )
    @DisplayName("잘못된 검색 필드는 400으로 거절하고 저장소를 호출하지 않는다")
    void invalidFilter(String field, String value) throws Exception {
        mvc
            .perform(
                get("/api/v1/problems").header("X-User-Id", "10").header("X-User-Role", "USER").param(field, value)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verifyNoInteractions(store);
    }

    /**
     * 비로그인 요청에 검색 결과를 노출하지 않는다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("비로그인 문제 검색은 401로 거절한다")
    void unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/problems")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
}
