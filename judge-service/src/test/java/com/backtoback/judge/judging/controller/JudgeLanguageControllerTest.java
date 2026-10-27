package com.backtoback.judge.judging.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.service.JudgeLanguageAvailability;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;

/**
 * 언어 조회 API의 인증과 서비스 결과의 공개 응답 변환을 검증한다.
 */
@WebMvcTest(JudgeLanguageController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class JudgeLanguageControllerTest {
    @MockitoBean
    private JudgeLanguageService languageService;

    @Autowired
    private MockMvc mvc;

    /**
     * 비로그인 사용자의 언어 조회를 공통 인증 응답으로 거부한다.
     *
     * @throws Exception API 요청 또는 검증에 실패한 경우
     */
    @Test
    @DisplayName("비로그인 사용자의 언어 조회를 공통 인증 응답으로 거부한다")
    void rejectsAnonymousRequest() throws Exception {
        mvc
            .perform(get("/api/v1/judge/languages"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    /**
     * Judge0 없이도 로그인 사용자가 미검증 언어 목록을 조회할 수 있는지 검증한다.
     *
     * @throws Exception API 요청 또는 검증에 실패한 경우
     */
    @Test
    @DisplayName("Judge0 없이도 로그인 사용자가 미검증 언어 목록을 조회할 수 있는지 검증한다")
    void returnsDisabledLanguagesWithoutJudge0() throws Exception {
        given(languageService.findAll())
            .willReturn(
                Arrays
                    .stream(JudgeLanguage.values())
                    .map(language -> new JudgeLanguageAvailability(language, null, false))
                    .toList()
            );
        mvc
            .perform(get("/api/v1/judge/languages").header("X-User-Id", "user-1").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content.length()").value(3))
            .andExpect(jsonPath("$.data.content[0].language").value("java"))
            .andExpect(jsonPath("$.data.content[0].displayName").value("Java 17"))
            .andExpect(jsonPath("$.data.content[0].runtimeVersion").value("배포 환경 확인 전"))
            .andExpect(jsonPath("$.data.content[0].enabled").value(false))
            .andExpect(jsonPath("$.data.content[0].judge0Id").doesNotExist())
            .andExpect(jsonPath("$.data.content[1].language").value("python3"))
            .andExpect(jsonPath("$.data.content[1].enabled").value(false))
            .andExpect(jsonPath("$.data.content[2].language").value("cpp"))
            .andExpect(jsonPath("$.data.content[2].enabled").value(false));
    }

    /**
     * 언어별 실행 환경 표시 정보를 변환하고 내부 실행 식별자를 숨기는지 검증한다.
     *
     * @throws Exception API 요청 또는 검증에 실패한 경우
     */
    @Test
    @DisplayName("언어별 실행 환경 표시 정보를 변환하고 내부 실행 식별자를 숨기는지 검증한다")
    void mapsRuntimeInformationWithoutExposingInternalIds() throws Exception {
        given(languageService.findAll())
            .willReturn(
                List
                    .of(
                        new JudgeLanguageAvailability(
                            JudgeLanguage.JAVA,
                            new JudgeRuntime("java", 91, "Java (JDK 17.0.6)", "17.0.6", null),
                            true
                        ),
                        new JudgeLanguageAvailability(
                            JudgeLanguage.PYTHON3,
                            new JudgeRuntime("python3", 92, "Python (3.11.2)", "3.11.2", null),
                            false
                        ),
                        new JudgeLanguageAvailability(
                            JudgeLanguage.CPP,
                            new JudgeRuntime("cpp", 93, "C++ (GCC 12.2.0)", "12.2.0", "-std=c++17"),
                            true
                        )
                    )
            );
        mvc
            .perform(get("/api/v1/judge/languages").header("X-User-Id", "user-1").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].runtimeVersion").value("JDK 17.0.6"))
            .andExpect(jsonPath("$.data.content[0].enabled").value(true))
            .andExpect(jsonPath("$.data.content[0].judge0Id").doesNotExist())
            .andExpect(jsonPath("$.data.content[1].runtimeVersion").value("3.11.2"))
            .andExpect(jsonPath("$.data.content[1].enabled").value(false))
            .andExpect(jsonPath("$.data.content[2].runtimeVersion").value("GCC 12.2.0"));
    }
}
