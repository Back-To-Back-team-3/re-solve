package com.backtoback.problem.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.admin.dto.AdminProblemSearchRequest;
import com.backtoback.problem.admin.service.AdminProblemQueryService;
import com.backtoback.problem.admin.service.AdminProblemQueryStore;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;

/** 관리자 목록의 인증과 반복 정렬 쿼리 바인딩을 검증한다. */
@WebMvcTest(AdminProblemQueryController.class)
@Import(
    {
        SecurityConfiguration.class,
        ProblemCreateBodyReader.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class AdminProblemQueryControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AdminProblemQueryStore store;
    @MockitoBean
    private AdminProblemQueryService service;

    /**
     * 반복 정렬 파라미터의 필드와 방향을 분리하지 않는다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("관리자 목록은 반복 정렬과 기본 검색 조건을 보존한다")
    void repeatedSort() throws Exception {
        given(store.list(any())).willAnswer(call -> {
            AdminProblemSearchRequest query = call.getArgument(0);
            assertThat(query.sort()).containsExactly("problemId,asc", "createdAt,desc");
            assertThat(query.pageable().getPageSize()).isEqualTo(20);
            return Page.empty();
        });
        mvc
            .perform(
                get("/api/v1/admin/problems")
                    .header("X-User-Id", "10")
                    .header("X-User-Role", "ADMIN")
                    .param("sort", "problemId,asc", "createdAt,desc")
            )
            .andExpect(status().isOk());
    }

    /**
     * 일반 회원에게 관리자 목록을 노출하지 않는다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("일반 회원은 관리자 문제 목록을 조회할 수 없다")
    void denied() throws Exception {
        mvc
            .perform(get("/api/v1/admin/problems").header("X-User-Id", "10").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
    }
}
