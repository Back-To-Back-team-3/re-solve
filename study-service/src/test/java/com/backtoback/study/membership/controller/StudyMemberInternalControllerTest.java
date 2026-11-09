package com.backtoback.study.membership.controller;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.dto.MembershipItemResponse;
import com.backtoback.study.membership.dto.MembershipListResponse;
import com.backtoback.study.membership.service.StudyMemberInternalService;

/**
 * §5.7.2 회원의 스터디 가입 목록 조회 내부 API 요청 바인딩·응답 변환을 검증한다.
 * {@code JpaAuditingConfiguration} 때문에 {@code @WebMvcTest}는 JPA metamodel 오류로 실패해(컨벤션 매뉴얼 6.7)
 * standalone MockMvc로 작성한다.
 */
class StudyMemberInternalControllerTest {

    private MockMvc mockMvc;
    private StudyMemberInternalService studyMemberInternalService;

    @BeforeEach
    void setUp() {
        studyMemberInternalService = mock(StudyMemberInternalService.class);
        StudyMemberInternalController controller = new StudyMemberInternalController(studyMemberInternalService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("5.7.2 memberId·role·activeOnly를 전달하면 200과 가입 목록을 반환한다")
    void getMemberMemberships_withAllParams_returns200AndItems() throws Exception {
        // given
        MembershipListResponse response
            = new MembershipListResponse("7", List.of(new MembershipItemResponse("12", "40", "LEADER", "ACTIVE")));
        given(studyMemberInternalService.findMemberMemberships(7L, MembershipRole.LEADER, true)).willReturn(response);

        // when & then
        mockMvc
            .perform(
                get("/internal/v1/studies/memberships")
                    .param("memberId", "7")
                    .param("role", "LEADER")
                    .param("activeOnly", "true")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.items[0].studyId").value("12"))
            .andExpect(jsonPath("$.items[0].membershipId").value("40"))
            .andExpect(jsonPath("$.items[0].role").value("LEADER"))
            .andExpect(jsonPath("$.items[0].studyStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("5.7.2 가입이 없는 회원에게는 200과 빈 items를 반환한다")
    void getMemberMemberships_noMemberships_returns200WithEmptyItems() throws Exception {
        // given
        MembershipListResponse response = new MembershipListResponse("7", List.of());
        given(studyMemberInternalService.findMemberMemberships(anyLong(), isNull(), anyBoolean())).willReturn(response);

        // when & then
        mockMvc
            .perform(get("/internal/v1/studies/memberships").param("memberId", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("5.7.2 activeOnly 기본값은 true이다")
    void getMemberMemberships_activeOnlyDefaultsToTrue() throws Exception {
        // given
        MembershipListResponse response = new MembershipListResponse("7", List.of());
        given(studyMemberInternalService.findMemberMemberships(7L, null, true)).willReturn(response);

        // when & then
        mockMvc.perform(get("/internal/v1/studies/memberships").param("memberId", "7")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("5.7.2 memberId가 없으면 400을 반환한다")
    void getMemberMemberships_missingMemberId_returns400() throws Exception {
        // when & then
        mockMvc.perform(get("/internal/v1/studies/memberships")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5.7.2 memberId가 숫자가 아니면 400을 반환한다")
    void getMemberMemberships_nonNumericMemberId_returns400() throws Exception {
        // when & then
        mockMvc
            .perform(get("/internal/v1/studies/memberships").param("memberId", "abc"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5.7.2 activeOnly=false이면 CLOSED 스터디 포함 응답을 반환한다")
    void getMemberMemberships_activeOnlyFalse_includesClosedStudy() throws Exception {
        // given
        MembershipListResponse response
            = new MembershipListResponse("7", List.of(new MembershipItemResponse("15", "55", "MEMBER", "CLOSED")));
        given(studyMemberInternalService.findMemberMemberships(7L, null, false)).willReturn(response);

        // when & then
        mockMvc
            .perform(get("/internal/v1/studies/memberships").param("memberId", "7").param("activeOnly", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].studyStatus").value("CLOSED"));
    }
}
