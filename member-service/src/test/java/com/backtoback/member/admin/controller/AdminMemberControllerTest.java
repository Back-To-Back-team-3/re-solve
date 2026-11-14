package com.backtoback.member.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.admin.service.AdminMemberService;
import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;

@WebMvcTest(AdminMemberController.class)
@Import(SecurityConfig.class)
class AdminMemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminMemberService adminMemberService;

    @Test
    @DisplayName("1.6.1 ADMIN은 조건으로 회원 목록을 조회하고 공통 페이지 응답과 +09:00 가입 시각을 받는다")
    void searchMembersReturnsPage() throws Exception {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", 7L);
        ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.of(2026, 10, 1, 10, 0));
        given(adminMemberService.searchMembers(eq("kim"), eq(MemberStatus.ACTIVE), eq(MemberRole.USER), any()))
            .willReturn(new PageImpl<>(List.of(member), PageRequest.of(0, 20), 1));

        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .param("keyword", "kim")
                    .param("status", "ACTIVE")
                    .param("role", "USER")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].memberId").value("7"))
            .andExpect(jsonPath("$.data.content[0].nickname").value("kim-dev"))
            .andExpect(jsonPath("$.data.content[0].githubLogin").value("kim-dev"))
            .andExpect(jsonPath("$.data.content[0].role").value("USER"))
            .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-01T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("1.6.1 createdAt 외 정렬은 버리고 기본 createdAt,desc를 쓰며, 크기는 100으로 제한하고 id로 순서를 고정한다")
    void searchMembersRestrictsSortAndSize() throws Exception {
        given(adminMemberService.searchMembers(any(), any(), any(), any())).willReturn(new PageImpl<>(List.of()));

        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .param("sort", "nickname,asc")
                    .param("size", "500")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isOk());
        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .param("sort", "createdAt,asc")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(adminMemberService, times(2)).searchMembers(any(), any(), any(), pageable.capture());
        assertThat(pageable.getAllValues().get(0).getPageSize()).isEqualTo(100);
        assertThat(pageable.getAllValues().get(0).getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        assertThat(pageable.getAllValues().get(1).getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt", "id"));
    }

    @Test
    @DisplayName("1.6.1 USER는 403 AUTH_ACCESS_DENIED, 없는 상태 값은 400")
    void searchMembersRequiresAdminAndValidStatus() throws Exception {
        mockMvc
            .perform(get("/api/v1/admin/members").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("AUTH_ACCESS_DENIED"));
        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .param("status", "DELETED")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
            )
            .andExpect(status().isBadRequest());

        verify(adminMemberService, never()).searchMembers(any(), any(), any(), any());
    }

    @Test
    @DisplayName("1.6.2 정지는 메시지와 바뀐 상태를 주고, 집행자 ID와 X-Forwarded-For 첫 주소를 서비스에 넘긴다")
    void changeStatusReturnsStatus() throws Exception {
        given(adminMemberService.changeStatus(1L, 7L, MemberStatus.SUSPENDED, "부정 행위 신고 확인", "203.0.113.7"))
            .willReturn(member(7L, MemberStatus.SUSPENDED, MemberRole.USER));

        mockMvc
            .perform(
                patch("/api/v1/admin/members/7/status")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .header("X-Forwarded-For", "203.0.113.7, 10.0.0.5")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUSPENDED\",\"reason\":\"부정 행위 신고 확인\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("회원 상태가 변경되었습니다."))
            .andExpect(jsonPath("$.data.memberId").value("7"))
            .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("1.6.3 권한 변경은 메시지와 바뀐 권한을 주고, 본인 권한 변경은 403 AUTH_ACCESS_DENIED")
    void changeRoleReturnsRole() throws Exception {
        given(adminMemberService.changeRole(eq(1L), eq(7L), eq(MemberRole.ADMIN), eq("운영진 합류"), eq("203.0.113.7")))
            .willReturn(member(7L, MemberStatus.ACTIVE, MemberRole.ADMIN));
        given(adminMemberService.changeRole(eq(1L), eq(1L), eq(MemberRole.USER), eq("사유"), any()))
            .willThrow(new MemberException(CommonErrorCode.AUTH_ACCESS_DENIED));

        mockMvc
            .perform(
                patch("/api/v1/admin/members/7/role")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .header("X-Forwarded-For", "203.0.113.7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"ADMIN\",\"reason\":\"운영진 합류\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("권한이 변경되었습니다."))
            .andExpect(jsonPath("$.data.role").value("ADMIN"));
        mockMvc
            .perform(
                patch("/api/v1/admin/members/1/role")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"USER\",\"reason\":\"사유\"}")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("AUTH_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("1.6.2·1.6.3 USER는 403, 사유가 없으면 400이고 서비스를 호출하지 않는다")
    void changeRequiresAdminAndReason() throws Exception {
        mockMvc
            .perform(
                patch("/api/v1/admin/members/7/status")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUSPENDED\",\"reason\":\"사유\"}")
            )
            .andExpect(status().isForbidden());
        mockMvc
            .perform(
                patch("/api/v1/admin/members/7/role")
                    .header("X-User-Id", "1")
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"ADMIN\",\"reason\":\" \"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].field").value("reason"));

        verify(adminMemberService, never()).changeStatus(any(), any(), any(), any(), any());
        verify(adminMemberService, never()).changeRole(any(), any(), any(), any(), any());
    }

    private Member member(Long id, MemberStatus status, MemberRole role) {
        Member member = Member.registerWithGitHub(1000L + id, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", id);
        ReflectionTestUtils.setField(member, "status", status);
        ReflectionTestUtils.setField(member, "role", role);
        return member;
    }
}
