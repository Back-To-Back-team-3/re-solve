package com.backtoback.member.skill.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.domain.TagLevel;
import com.backtoback.member.skill.service.TagLevelService;

@WebMvcTest(InternalTagLevelController.class)
@Import(SecurityConfig.class)
class InternalTagLevelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TagLevelService tagLevelService;

    @Test
    @DisplayName("1.7.2 Gateway 인증 정보 없이 호출하고, 공통 응답 없이 진입한 태그의 레벨을 준다")
    void returnsEnteredTagLevels() throws Exception {
        MemberTagLevel hash = MemberTagLevel.enterWithoutDiagnosis(7L, 3L, LocalDateTime.of(2026, 10, 20, 9, 0));
        MemberTagLevel stack = MemberTagLevel.enterWithoutDiagnosis(7L, 4L, LocalDateTime.of(2026, 10, 21, 9, 0));
        ReflectionTestUtils.setField(stack, "level", TagLevel.LV2);
        given(tagLevelService.getEnteredTagLevels(7L)).willReturn(List.of(hash, stack));

        mockMvc
            .perform(get("/internal/v1/members/7/tag-levels"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.tagLevels[0].tagId").value("3"))
            .andExpect(jsonPath("$.tagLevels[0].level").value("LV1"))
            .andExpect(jsonPath("$.tagLevels[1].tagId").value("4"))
            .andExpect(jsonPath("$.tagLevels[1].level").value("LV2"));
    }

    @Test
    @DisplayName("1.7.2 진입한 태그가 없으면 빈 배열, 회원 ID가 숫자가 아니면 400")
    void handlesEmptyAndInvalidMemberId() throws Exception {
        given(tagLevelService.getEnteredTagLevels(8L)).willReturn(List.of());

        mockMvc
            .perform(get("/internal/v1/members/8/tag-levels"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tagLevels").isEmpty());
        mockMvc
            .perform(get("/internal/v1/members/abc/tag-levels"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }
}
