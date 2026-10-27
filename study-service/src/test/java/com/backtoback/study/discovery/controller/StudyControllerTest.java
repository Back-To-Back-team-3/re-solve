package com.backtoback.study.discovery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.discovery.service.StudyQueryService;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 스터디 목록 조회 API의 요청 바인딩·응답 변환을 검증한다. {@code JpaAuditingConfiguration} 때문에
 * {@code @WebMvcTest}는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7) 표준 Spring 컨텍스트 없이
 * 컨트롤러만 등록하는 standalone MockMvc로 작성한다(notification-service의 기존 테스트와 같은 방식).
 */
class StudyControllerTest {

    private MockMvc mockMvc;
    private StudyQueryService studyQueryService;

    @BeforeEach
    void setUp() {
        studyQueryService = mock(StudyQueryService.class);
        StudyController controller = new StudyController(studyQueryService);
        mockMvc
            = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("5.1.1 필터 없이 요청하면 조회 결과를 공통 응답으로 반환한다")
    void searchStudiesSuccess() throws Exception {
        // given
        StudySummaryResponse item
            = new StudySummaryResponse(
                12L,
                "코딩테스트 스터디",
                "EMPLOYMENT",
                "LV2",
                "java",
                10,
                5,
                StudyStatus.RECRUITING,
                JoinType.APPROVAL,
                LocalDateTime.of(2026, 9, 27, 21, 10),
                LocalDateTime.of(2026, 9, 21, 10, 0)
            );
        given(studyQueryService.searchStudies(any(), any(), any(), any(), any(), any()))
            .willReturn(new PageImpl<>(List.of(item)));

        // when & then
        mockMvc
            .perform(get("/api/v1/studies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].studyId").value(12))
            .andExpect(jsonPath("$.data.content[0].name").value("코딩테스트 스터디"));
    }

    @Test
    @DisplayName("5.1.1 size가 100을 넘으면 100으로 낮춰 서비스에 전달한다")
    void clampsPageSizeTo100() throws Exception {
        // given
        given(studyQueryService.searchStudies(any(), any(), any(), any(), any(), any()))
            .willReturn(new PageImpl<>(List.of()));

        // when
        mockMvc.perform(get("/api/v1/studies").param("size", "1000")).andExpect(status().isOk());

        // then
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(studyQueryService).searchStudies(any(), any(), any(), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("5.1.1 허용되지 않은 정렬 필드를 요청하면 기본 정렬(createdAt desc)로 대체한다")
    void restrictsSortToAllowedFields() throws Exception {
        // given
        given(studyQueryService.searchStudies(any(), any(), any(), any(), any(), any()))
            .willReturn(new PageImpl<>(List.of()));

        // when
        mockMvc.perform(get("/api/v1/studies").param("sort", "name,asc")).andExpect(status().isOk());

        // then
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(studyQueryService).searchStudies(any(), any(), any(), any(), any(), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().iterator().next();
        assertThat(order.getProperty()).isEqualTo("createdAt");
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }
}
