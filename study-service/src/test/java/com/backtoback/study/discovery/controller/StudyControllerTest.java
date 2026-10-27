package com.backtoback.study.discovery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.study.discovery.dto.StudyDetailResponse;
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.discovery.service.StudyQueryService;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyStatus;

/**
 * 스터디 목록·상세 조회 API의 요청 바인딩·응답 변환을 검증한다. {@code JpaAuditingConfiguration} 때문에
 * {@code @WebMvcTest}는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7) 표준 Spring 컨텍스트 없이
 * 컨트롤러만 등록하는 standalone MockMvc로 작성한다(notification-service의 기존 테스트와 같은 방식).
 * {@code @AuthenticationPrincipal}은 Spring Security 필터 체인 없이 {@code AuthenticationPrincipalArgumentResolver}만
 * 등록하고, {@code SecurityContextHolder}에 직접 인증 정보를 넣어 로그인·비로그인 분기를 검증한다.
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
                .setCustomArgumentResolvers(
                    new PageableHandlerMethodArgumentResolver(),
                    new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("5.1.1 필터 없이 요청하면 조회 결과를 공통 응답으로 반환한다")
    void searchStudiesSuccess() throws Exception {
        // given
        StudySummaryResponse item
            = new StudySummaryResponse(
                "12",
                "코딩테스트 스터디",
                "EMPLOYMENT",
                "LV2",
                "java",
                "EVENING",
                10,
                5,
                StudyStatus.RECRUITING,
                JoinType.APPROVAL,
                OffsetDateTime.of(2026, 9, 27, 21, 10, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 9, 21, 10, 0, 0, 0, ZoneOffset.ofHours(9))
            );
        given(studyQueryService.searchStudies(any(), any(), any(), any(), any(), any()))
            .willReturn(new PageImpl<>(List.of(item)));

        // when & then
        mockMvc
            .perform(get("/api/v1/studies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].studyId").value("12"))
            .andExpect(jsonPath("$.data.content[0].name").value("코딩테스트 스터디"))
            .andExpect(jsonPath("$.data.content[0].activeTimeSlot").value("EVENING"))
            .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-21T10:00:00+09:00"));
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

    @Test
    @DisplayName("5.1.2 비로그인으로 상세 조회하면 서비스에 memberId 없이 전달한다")
    void getStudyDetailAnonymous() throws Exception {
        // given
        StudyDetailResponse response = detailResponse(null);
        given(studyQueryService.getStudyDetail(eq(12L), isNull())).willReturn(response);

        // when & then
        mockMvc
            .perform(get("/api/v1/studies/12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.studyId").value("12"))
            .andExpect(jsonPath("$.data.myMembership").isEmpty());
    }

    @Test
    @DisplayName("5.1.2 로그인 시 토큰의 사용자 ID를 memberId로 파싱해 서비스에 전달한다")
    void getStudyDetailAuthenticated() throws Exception {
        // given
        SecurityContextHolder
            .getContext()
            .setAuthentication(new TestingAuthenticationToken(new GatewayUser("7", UserRole.USER), null));
        StudyDetailResponse.MyMembership myMembership
            = new StudyDetailResponse.MyMembership("55", MembershipStatus.APPROVED, MembershipRole.MEMBER);
        StudyDetailResponse response = detailResponse(myMembership);
        given(studyQueryService.getStudyDetail(eq(12L), eq(7L))).willReturn(response);

        // when & then
        mockMvc
            .perform(get("/api/v1/studies/12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.myMembership.membershipId").value("55"))
            .andExpect(jsonPath("$.data.myMembership.status").value("APPROVED"));
    }

    private StudyDetailResponse detailResponse(StudyDetailResponse.MyMembership myMembership) {
        return new StudyDetailResponse(
            "12",
            "코딩테스트 스터디",
            "취업 준비 알고리즘 스터디",
            "EMPLOYMENT",
            "LV2",
            "java",
            "EVENING",
            10,
            5,
            StudyStatus.RECRUITING,
            JoinType.APPROVAL,
            true,
            3L,
            null,
            myMembership,
            LocalDateTime.of(2026, 9, 21, 10, 0)
        );
    }
}
