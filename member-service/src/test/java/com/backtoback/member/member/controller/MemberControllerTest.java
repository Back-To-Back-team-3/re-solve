package com.backtoback.member.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.service.MemberService;

@WebMvcTest(MemberController.class)
@Import(SecurityConfig.class)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    @DisplayName("1.2.1 내 정보 조회는 계정 정보와 학습 프로필 입력 여부, +09:00 가입 시각을 준다")
    void getMeReturnsMember() throws Exception {
        Member member = member(7L, "kim-dev");
        ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.of(2026, 10, 1, 10, 0));
        given(memberService.getMember(7L)).willReturn(member);

        mockMvc
            .perform(get("/api/v1/members/me").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberId").value("7"))
            .andExpect(jsonPath("$.data.githubLogin").value("kim-dev"))
            .andExpect(jsonPath("$.data.nickname").value("kim-dev"))
            .andExpect(jsonPath("$.data.profileImageUrl").value("https://avatars.githubusercontent.com/u/1001"))
            .andExpect(jsonPath("$.data.email").isEmpty())
            .andExpect(jsonPath("$.data.role").value("USER"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.hasLearningProfile").value(false))
            .andExpect(jsonPath("$.data.createdAt").value("2026-10-01T10:00:00+09:00"));
    }

    @Test
    @DisplayName("1.2.1 Gateway 인증 정보가 없으면 401 AUTH_TOKEN_INVALID")
    void getMeRequiresAuthentication() throws Exception {
        mockMvc
            .perform(get("/api/v1/members/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        verify(memberService, never()).getMember(any());
    }

    @Test
    @DisplayName("1.2.2 내 정보 수정은 바뀐 닉네임과 안내 메시지를 준다")
    void updateMeReturnsNickname() throws Exception {
        given(memberService.updateNickname(7L, "kim-algo")).willReturn(member(7L, "kim-algo"));

        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"kim-algo\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("회원 정보가 수정되었습니다."))
            .andExpect(jsonPath("$.data.memberId").value("7"))
            .andExpect(jsonPath("$.data.nickname").value("kim-algo"));
    }

    @Test
    @DisplayName("1.2.2 정지 회원은 403 MEMBER_SUSPENDED, 닉네임 형식 위반은 400 MEMBER_NICKNAME_INVALID")
    void updateMeMapsErrors() throws Exception {
        given(memberService.updateNickname(7L, "kim-algo"))
            .willThrow(new MemberException(MemberErrorCode.MEMBER_SUSPENDED));
        given(memberService.updateNickname(7L, "a"))
            .willThrow(new MemberException(MemberErrorCode.MEMBER_NICKNAME_INVALID));

        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"kim-algo\"}")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("MEMBER_SUSPENDED"));
        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"a\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MEMBER_NICKNAME_INVALID"));
    }

    @Test
    @DisplayName("1.2.2 Gateway 인증 정보가 없으면 401 AUTH_TOKEN_INVALID이고 수정하지 않는다")
    void updateMeRequiresAuthentication() throws Exception {
        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"kim-algo\"}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        verify(memberService, never()).updateNickname(any(), any());
    }

    private Member member(Long id, String nickname) {
        Member member
            = Member.registerWithGitHub(1001L, "kim-dev", null, "https://avatars.githubusercontent.com/u/1001");
        ReflectionTestUtils.setField(member, "id", id);
        ReflectionTestUtils.setField(member, "nickname", nickname);
        ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.of(2026, 10, 1, 10, 0));
        return member;
    }
}
