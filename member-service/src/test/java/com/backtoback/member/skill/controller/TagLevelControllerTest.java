package com.backtoback.member.skill.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.skill.config.SkillProperties.Promotion;
import com.backtoback.member.skill.domain.MemberTagLevel;
import com.backtoback.member.skill.domain.TagLevel;
import com.backtoback.member.skill.service.TagLevelService;
import com.backtoback.member.skill.service.TagLevelSummary;

@WebMvcTest(TagLevelController.class)
@Import(SecurityConfig.class)
class TagLevelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TagLevelService tagLevelService;

    @Test
    @DisplayName("1.4.1 태그 레벨 목록은 레벨·최대 난이도·진단 가능 여부(+09:00)·다음 승급 기준을 준다")
    void getTagLevelsReturnsSummaries() throws Exception {
        MemberTagLevel tagLevel = MemberTagLevel.enterWithoutDiagnosis(7L, 3L, LocalDateTime.of(2026, 10, 20, 9, 0));
        given(tagLevelService.getTagLevels(7L))
            .willReturn(
                List
                    .of(
                        new TagLevelSummary(
                            tagLevel,
                            false,
                            LocalDateTime.of(2026, 10, 31, 12, 0),
                            new Promotion(TagLevel.LV2, 5, new BigDecimal("60.00"))
                        )
                    )
            );

        mockMvc
            .perform(get("/api/v1/members/me/tag-levels").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].tagId").value("3"))
            .andExpect(jsonPath("$.data.content[0].level").value("LV1"))
            .andExpect(jsonPath("$.data.content[0].accessibleMaxDifficulty").value(1))
            .andExpect(jsonPath("$.data.content[0].achievements").isEmpty())
            .andExpect(jsonPath("$.data.content[0].diagnosis.isEligible").value(false))
            .andExpect(jsonPath("$.data.content[0].diagnosis.cooldownEndsAt").value("2026-10-31T12:00:00+09:00"))
            .andExpect(jsonPath("$.data.content[0].diagnosis.inProgressDiagnosisId").isEmpty())
            .andExpect(jsonPath("$.data.content[0].nextPromotion.targetLevel").value("LV2"))
            .andExpect(jsonPath("$.data.content[0].nextPromotion.requiredSolvedCount").value(5))
            .andExpect(jsonPath("$.data.content[0].nextPromotion.requiredAchievementRate").value(60.00));
    }

    @Test
    @DisplayName("1.4.2 태그 진입은 201과 안내 메시지, LV1을 준다")
    void enterReturnsCreated() throws Exception {
        given(tagLevelService.enter(7L, 3L))
            .willReturn(MemberTagLevel.enterWithoutDiagnosis(7L, 3L, LocalDateTime.of(2026, 10, 29, 12, 0)));

        mockMvc
            .perform(
                post("/api/v1/members/me/tag-levels")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tagId\":\"3\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("Lv.1로 시작합니다."))
            .andExpect(jsonPath("$.data.tagId").value("3"))
            .andExpect(jsonPath("$.data.level").value("LV1"));
    }

    @Test
    @DisplayName("1.4.2 숫자가 아닌 태그 ID는 400, 이미 진입한 태그는 409 LEVEL_ALREADY_ENTERED")
    void enterMapsErrors() throws Exception {
        mockMvc
            .perform(
                post("/api/v1/members/me/tag-levels")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tagId\":\"abc\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details[0].field").value("tagId"));

        given(tagLevelService.enter(7L, 3L)).willThrow(new MemberException(MemberErrorCode.LEVEL_ALREADY_ENTERED));
        mockMvc
            .perform(
                post("/api/v1/members/me/tag-levels")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tagId\":\"3\"}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("LEVEL_ALREADY_ENTERED"));
    }

    @Test
    @DisplayName("1.4.1·1.4.2 Gateway 인증 정보가 없으면 401이고 서비스를 호출하지 않는다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/members/me/tag-levels")).andExpect(status().isUnauthorized());
        mockMvc
            .perform(
                post("/api/v1/members/me/tag-levels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tagId\":\"3\"}")
            )
            .andExpect(status().isUnauthorized());

        verify(tagLevelService, never()).getTagLevels(any());
        verify(tagLevelService, never()).enter(any(), any());
    }
}
