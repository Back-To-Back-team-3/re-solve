package com.backtoback.member.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.admin.service.AdminMemberService;
import com.backtoback.member.global.config.SecurityConfig;
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
}
