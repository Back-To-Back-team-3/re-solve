package com.backtoback.member.skill.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.member.global.config.SecurityConfig;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.skill.client.ProblemDiagnosisClient.Candidate;
import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.service.DiagnosisCooldownException;
import com.backtoback.member.skill.service.DiagnosisService;
import com.backtoback.member.skill.service.DiagnosisService.StartedDiagnosis;
import com.backtoback.member.skill.store.DiagnosisDraftStore.Draft;
import com.backtoback.member.skill.store.DiagnosisDraftStore.SaveResult;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore.Submission;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(DiagnosisController.class)
@Import(SecurityConfig.class)
class DiagnosisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DiagnosisService diagnosisService;

    /**
     * 본인 이미지 응답의 MIME·비공개 캐시·바이너리를 확인한다.
     *
     * @throws Exception MockMvc 요청을 처리하지 못하는 경우
     */
    @Test
    @DisplayName("진단 이미지는 USER 요청에 바이너리와 private no-store 및 nosniff를 반환한다")
    void imageResponse() throws Exception {
        byte[] bytes
            = {
                1,
                2,
                3
            };
        given(diagnosisService.getProblemImage(7L, 120L, 301L, 701L))
            .willReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(bytes));
        mockMvc
            .perform(
                get("/api/v1/diagnoses/120/problems/301/images/701")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(content().bytes(bytes))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    /**
     * 다른 회원의 이미지 요청에는 JSON 오류를 반환하는지 확인한다.
     *
     * @throws Exception MockMvc 요청을 처리하지 못하는 경우
     */
    @Test
    @DisplayName("권한이 없는 진단 이미지와 로그인하지 않은 이미지 조회를 거절한다")
    void imageNotFoundOrUnauthenticated() throws Exception {
        given(diagnosisService.getProblemImage(8L, 120L, 301L, 701L))
            .willThrow(new MemberException(MemberErrorCode.DIAGNOSIS_NOT_FOUND));
        mockMvc
            .perform(
                get("/api/v1/diagnoses/120/problems/301/images/701")
                    .header("X-User-Id", "8")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("DIAGNOSIS_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/diagnoses/120/problems/301/images/701")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("1.5.1 진단 시작은 201과 문자열 ID, +09:00 시각, 출제 문항을 준다")
    void startReturnsCreated() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 10, 3, 20, 0);
        DiagnosisAttempt attempt = DiagnosisAttempt.start(7L, 3L, "해시", now);
        ReflectionTestUtils.setField(attempt, "id", 120L);
        given(diagnosisService.start(7L, 3L))
            .willReturn(new StartedDiagnosis(attempt, List.of(new Candidate(1, "301", "1001", "두 수의 합")), now));

        mockMvc
            .perform(
                post("/api/v1/diagnoses")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tagId\":\"3\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.message").value("진단을 시작합니다."))
            .andExpect(jsonPath("$.data.diagnosisId").value("120"))
            .andExpect(jsonPath("$.data.startedAt").value("2026-10-03T20:00:00+09:00"))
            .andExpect(jsonPath("$.data.endsAt").value("2026-10-03T21:00:00+09:00"))
            .andExpect(jsonPath("$.data.problems[0].problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.data.problems[0].title").value("두 수의 합"));
    }

    @Test
    @DisplayName("1.5.1 쿨타임 중이면 409 DIAGNOSIS_COOLDOWN과 Retry-After(초)를 준다")
    void cooldownAddsRetryAfter() throws Exception {
        given(diagnosisService.start(7L, 3L)).willThrow(new DiagnosisCooldownException(3600));

        mockMvc
            .perform(
                post("/api/v1/diagnoses")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tagId\":\"3\"}")
            )
            .andExpect(status().isConflict())
            .andExpect(header().string("Retry-After", "3600"))
            .andExpect(jsonPath("$.error.code").value("DIAGNOSIS_COOLDOWN"));
    }

    @Test
    @DisplayName("1.5.4 Draft 저장은 요청 seq가 반영됐는지와 서버 seq를 주고, 잘못된 언어·순번은 400이다")
    void saveDraft() throws Exception {
        Draft saved = new Draft("python3", "code", 15L, LocalDateTime.of(2026, 10, 3, 20, 21, 12));
        given(diagnosisService.saveDraft(7L, 120L, 301L, "python3", "code", 15L))
            .willReturn(new SaveResult(true, saved));
        given(diagnosisService.saveDraft(7L, 120L, 301L, "python3", "old", 3L))
            .willReturn(new SaveResult(false, saved));

        mockMvc
            .perform(draft("{\"language\":\"python3\",\"sourceCode\":\"code\",\"seq\":\"15\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.seq").value("15"))
            .andExpect(jsonPath("$.data.savedAt").value("2026-10-03T20:21:12+09:00"));
        mockMvc
            .perform(draft("{\"language\":\"python3\",\"sourceCode\":\"old\",\"seq\":\"3\"}"))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.seq").value("15"));
        for (String body : List
            .of(
                "{\"language\":\"kotlin\",\"sourceCode\":\"x\",\"seq\":\"1\"}",
                "{\"language\":\"java\",\"sourceCode\":\"x\",\"seq\":\"-1\"}",
                "{\"language\":\"java\",\"seq\":\"1\"}"
            )) {
            mockMvc
                .perform(draft(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        }
        // 앞의 정상 요청 2건만 저장을 호출한다.
        verify(diagnosisService, times(2)).saveDraft(anyLong(), anyLong(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("1.5.3 문제 본문은 problem 응답을 data에 그대로 담는다")
    void problemContentPassesThrough() throws Exception {
        given(diagnosisService.getProblemContent(7L, 120L, 301L))
            .willReturn(objectMapper.readTree("{\"problemId\":\"301\",\"title\":\"두 수의 합\",\"samples\":[]}"));

        mockMvc
            .perform(get("/api/v1/diagnoses/120/problems/301").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.problemId").value("301"))
            .andExpect(jsonPath("$.data.title").value("두 수의 합"));
    }

    @Test
    @DisplayName("1.5.5 문항 제출은 202와 문자열 접수 ID·상태·+09:00 접수 시각을 주고, 5초 안 재제출은 429다")
    void submitReturnsAccepted() throws Exception {
        given(diagnosisService.submit(7L, 120L, 301L, "python3", "code", 16L))
            .willReturn(new Submission(880L, "ACCEPTED", LocalDateTime.of(2026, 10, 3, 20, 22)));
        given(diagnosisService.submit(7L, 120L, 301L, "python3", "code", 17L))
            .willThrow(new MemberException(MemberErrorCode.SUBMISSION_RATE_LIMITED));

        mockMvc
            .perform(submission("{\"language\":\"python3\",\"sourceCode\":\"code\",\"seq\":\"16\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("제출이 접수되었습니다."))
            .andExpect(jsonPath("$.data.diagnosisSubmissionId").value("880"))
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.receivedAt").value("2026-10-03T20:22:00+09:00"));
        mockMvc
            .perform(submission("{\"language\":\"python3\",\"sourceCode\":\"code\",\"seq\":\"17\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_RATE_LIMITED"));
        mockMvc
            .perform(submission("{\"language\":\"python3\",\"sourceCode\":\"   \",\"seq\":\"18\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("1.5.6 최종 제출은 CLOSED와 +09:00 종료 시각, 안내 메시지를 준다")
    void finishReturnsClosed() throws Exception {
        DiagnosisAttempt attempt = DiagnosisAttempt.start(7L, 3L, "해시", LocalDateTime.of(2026, 10, 3, 20, 0));
        ReflectionTestUtils.setField(attempt, "id", 120L);
        attempt.close("SUBMITTED", LocalDateTime.of(2026, 10, 3, 20, 40));
        given(diagnosisService.finish(7L, 120L)).willReturn(attempt);

        mockMvc
            .perform(post("/api/v1/diagnoses/120/finish").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("진단이 종료되었습니다. 채점이 끝나면 결과를 알려드립니다."))
            .andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.closedAt").value("2026-10-03T20:40:00+09:00"));
    }

    private org.springframework.test.web.servlet.RequestBuilder submission(String body) {
        return post("/api/v1/diagnoses/120/problems/301/submissions")
            .header("X-User-Id", "7")
            .header("X-User-Role", "USER")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private org.springframework.test.web.servlet.RequestBuilder draft(String body) {
        return put("/api/v1/diagnoses/120/problems/301/draft")
            .header("X-User-Id", "7")
            .header("X-User-Role", "USER")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }
}
