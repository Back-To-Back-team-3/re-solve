package com.backtoback.judge.judging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.judge0.client.Judge0LanguageClient;
import com.backtoback.judge.judging.controller.JudgeLanguageController;
import com.backtoback.judge.judging.service.JudgeLanguageService;

@WebMvcTest(JudgeLanguageController.class)
@Import(
    {
        JudgeLanguageService.class,
        Judge0LanguageClient.class,
        JudgeLanguageTests.HttpConfiguration.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
@org.springframework.test.context.TestPropertySource(properties = "judge0.base-url=")
class JudgeLanguageTests {
    @Autowired
    private MockMvc mvc;

    /**
     * 비로그인 사용자의 언어 조회를 공통 인증 응답으로 거부한다.
     *
     * @throws Exception API 요청 또는 검증에 실패한 경우
     */
    @Test
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
    void returnsDisabledLanguagesWithoutJudge0() throws Exception {
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

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class HttpConfiguration {
        /**
         * 주소 미설정 시 외부 호출이 없는 웹 테스트용 클라이언트를 제공한다.
         *
         * @return 테스트용 HTTP 클라이언트
         */
        @org.springframework.context.annotation.Bean
        org.springframework.web.client.RestClient judge0RestClient() {
            return org.springframework.web.client.RestClient.builder().build();
        }
    }
}
