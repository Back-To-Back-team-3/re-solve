package com.backtoback.member.member.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.service.MemberService;

@WebMvcTest(InternalMemberController.class)
@Import(SecurityConfig.class)
class InternalMemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    @DisplayName("1.7.1 Gateway 인증 정보 없이 호출하고, 공통 응답 없이 회원 ID·상태·권한을 준다")
    void returnsMemberStatus() throws Exception {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "id", 7L);
        ReflectionTestUtils.setField(member, "status", MemberStatus.SUSPENDED);
        given(memberService.getMember(7L)).willReturn(member);

        mockMvc
            .perform(get("/internal/v1/members/7/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.status").value("SUSPENDED"))
            .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("1.7.1 없는 회원은 404 MEMBER_NOT_FOUND, 회원 ID가 숫자가 아니면 400")
    void handlesMissingAndInvalidMember() throws Exception {
        given(memberService.getMember(99L)).willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        mockMvc
            .perform(get("/internal/v1/members/99/status"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
        mockMvc
            .perform(get("/internal/v1/members/abc/status"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }
}
