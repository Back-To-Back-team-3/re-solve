package com.backtoback.problem.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.dto.response.AdminValidationProgressResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ValidationProgressService;
import com.backtoback.problem.problem.service.ValidationProgressStore;

/** 검증 접수의 관리자 권한·추적 ID와 내부 조회 응답 계약을 확인한다. */
@WebMvcTest(AdminValidationProgressController.class)
@Import(
    {
        SecurityConfiguration.class,
        ProblemCreateBodyReader.class,
        CorrelationIdFilter.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class AdminValidationProgressControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ValidationProgressService service;

    /**
     * 관리자 라우트에서 조회 응답을 공통 형식으로 감싸고 일반 사용자 접근을 차단한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("관리자 진행 조회는 작성자 검사용 ID를 전달하고 일반 사용자는 거절한다")
    void progress() throws Exception {
        var local = new ValidationProgressStore.Snapshot("3", "1", "2", "java", false, List.of(), null);
        given(service.read(3, 10)).willReturn(AdminValidationProgressResponse.from(local, null));
        String path = "/api/v1/admin/problems/validations/3";
        mvc
            .perform(get(path).header("X-User-Id", "10").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REQUESTED"))
            .andExpect(jsonPath("$.data.resultApplied").value(false));
        mvc
            .perform(get(path).header("X-User-Id", "10").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
        verify(service).read(3, 10);
    }
}
