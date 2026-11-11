package com.backtoback.judge.submission.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.domain.SubmissionDraft;
import com.backtoback.judge.submission.repository.SubmissionRepository;
import com.backtoback.judge.submission.service.InternalSubmissionQueryService;

/** Contest 내부 조회의 성공 응답과 원본 코드의 접근 경계를 검증한다. */
@WebMvcTest(InternalSubmissionQueryController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        InternalSubmissionQueryService.class
    }
)
class InternalSubmissionQueryControllerTest {
    private static final String RECEIPT = "/internal/v1/judge/submissions/by-receipt";
    private static final String RESULT = "/internal/v1/submissions/2002/result";
    private static final String SOURCE = "/internal/v1/submissions/2002/source";
    @MockitoBean
    private SubmissionRepository submissions;
    @Autowired
    private MockMvc mvc;

    /**
     * 미접수 상태와 허용하지 않는 문맥을 구분한다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("미접수는 found=false만 반환하고 진단 문맥은 거절한다")
    void distinguishesMissingReceipt() throws Exception {
        mvc
            .perform(get(RECEIPT).param("context", "EXAM").param("receiptId", "4001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.found").value(false))
            .andExpect(jsonPath("$.length()").value(1));
        mvc
            .perform(get(RECEIPT).param("context", "DIAGNOSIS").param("receiptId", "4001"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").value("context"));
    }

    /**
     * 원래 접수 ID의 형식과 BIGINT 범위 위반을 공통 요청 오류로 반환하는지 확인한다.
     *
     * @param receiptId 유효하지 않은 접수 ID
     * @throws Exception 요청 실행 실패
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "0",
            "04001",
            "9223372036854775808"
        }
    )
    @DisplayName("접수 ID가 양수 십진수 형식 또는 BIGINT 범위를 벗어나면 필드 검증 오류다")
    void rejectsInvalidReceiptId(String receiptId) throws Exception {
        mvc
            .perform(get(RECEIPT).param("context", "EXAM").param("receiptId", receiptId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").value("receiptId"));
    }

    /**
     * 결과 이벤트가 도착하기 전에도 접수 ID와 제출 ID로 완료 상태를 확인한다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("접수 대사는 원본 식별자와 완료 판정을, 제출 조회는 현재 회차 결과만 반환한다")
    void returnsCompletedReceiptAndResult() throws Exception {
        var submission = submission(Submission.Context.EXAM, Submission.Status.COMPLETED);
        given(submissions.findByContextAndSourceSubmissionId(Submission.Context.EXAM, 4001))
            .willReturn(Optional.of(submission));
        given(submissions.findById(2002L)).willReturn(Optional.of(submission));

        mvc
            .perform(get(RECEIPT).param("context", "EXAM").param("receiptId", "4001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.found").value(true))
            .andExpect(jsonPath("$.submissionId").value("2002"))
            .andExpect(jsonPath("$.context").value("EXAM"))
            .andExpect(jsonPath("$.contextId").value("401"))
            .andExpect(jsonPath("$.receiptId").value("4001"))
            .andExpect(jsonPath("$.userId").value("7"))
            .andExpect(jsonPath("$.problemId").value("301"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.submittedAt").value("2026-09-28T10:00:00.000000+09:00"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.judgeAttempt").value(1))
            .andExpect(jsonPath("$.verdict").value("AC"))
            .andExpect(jsonPath("$.passedCount").value(12))
            .andExpect(jsonPath("$.totalCount").value(12))
            .andExpect(jsonPath("$.data").doesNotHaveJsonPath());
        mvc
            .perform(get(RESULT).param("contextId", "401"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sourceSubmissionId").value("4001"))
            .andExpect(jsonPath("$.submittedAt").value("2026-09-28T10:00:00.000000+09:00"))
            .andExpect(jsonPath("$.verdict").value("AC"))
            .andExpect(jsonPath("$.maxTimeMs").value(12))
            .andExpect(jsonPath("$.maxMemoryKb").value(8192))
            .andExpect(jsonPath("$.tests").doesNotHaveJsonPath())
            .andExpect(jsonPath("$.sourceCode").doesNotHaveJsonPath());
    }

    /**
     * 제출에 이전 결과가 있어도 현재 회차가 완료 상태가 아니면 반환하지 않는다.
     *
     * @param submissionStatus 현재 제출 상태
     * @throws Exception 요청 실행 실패
     */
    @ParameterizedTest
    @EnumSource(
        value = Submission.Status.class,
        names = {
            "COMPLETED"
        },
        mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("미종결·실패 상태는 대표 판정과 통계를 반환하지 않는다")
    void hidesPreviousResult(Submission.Status submissionStatus) throws Exception {
        var submission = submission(Submission.Context.CONTEST, submissionStatus);
        given(submissions.findByContextAndSourceSubmissionId(Submission.Context.CONTEST, 4001))
            .willReturn(Optional.of(submission));
        given(submissions.findById(2002L)).willReturn(Optional.of(submission));

        mvc
            .perform(get(RECEIPT).param("context", "CONTEST").param("receiptId", "4001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(submissionStatus.name()))
            .andExpect(jsonPath("$.verdict").value(nullValue()))
            .andExpect(jsonPath("$.passedCount").value(nullValue()))
            .andExpect(jsonPath("$.totalCount").value(nullValue()));
        mvc
            .perform(get(RESULT).param("contextId", "401"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value(nullValue()))
            .andExpect(jsonPath("$.maxTimeMs").value(nullValue()));
    }

    /**
     * 시험·대회 문맥이 아니거나 문맥 ID가 다르면 결과를 감춘다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("다른 시험 ID 또는 진단 문맥은 제출 결과를 조회할 수 없다")
    void rejectsMismatchedResult() throws Exception {
        given(submissions.findById(2002L))
            .willReturn(Optional.of(submission(Submission.Context.EXAM, Submission.Status.COMPLETED)))
            .willReturn(Optional.of(submission(Submission.Context.DIAGNOSIS, Submission.Status.COMPLETED)));
        mvc.perform(get(RESULT).param("contextId", "402")).andExpect(status().isNotFound());
        mvc.perform(get(RESULT).param("contextId", "401")).andExpect(status().isNotFound());
    }

    /**
     * 시험 코드가 소유자 및 시험 ID와 일치할 때만 공개되는지 확인한다.
     *
     * @throws Exception 요청 실행 실패
     */
    @Test
    @DisplayName("본인 시험 코드에만 접근할 수 있고 성공 응답의 캐시를 금지한다")
    void restrictsExamReviewSource() throws Exception {
        given(submissions.findByIdAndMemberIdAndContext(2002, 7, Submission.Context.EXAM))
            .willReturn(Optional.of(submission(Submission.Context.EXAM, Submission.Status.COMPLETED)));
        mvc
            .perform(get(SOURCE).param("purpose", "EXAM_REVIEW").param("memberId", "7").param("contextId", "401"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.sourceCode").value("sample code"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"));
        mvc
            .perform(get(SOURCE).param("purpose", "EXAM_REVIEW").param("memberId", "8").param("contextId", "401"))
            .andExpect(status().isNotFound());
        mvc
            .perform(get(SOURCE).param("purpose", "EXAM_REVIEW").param("memberId", "7").param("contextId", "402"))
            .andExpect(status().isNotFound());
    }

    /**
     * 고정 회차와 기존 결과를 가진 제출 행을 구성한다.
     *
     * @param context 외부 제출 문맥
     * @param status 현재 채점 상태
     * @return 조회할 제출 행
     */
    private Submission submission(Submission.Context context, Submission.Status status) {
        var submission
            = Submission
                .queued(
                    new SubmissionDraft(
                        7,
                        301,
                        1001,
                        context,
                        401L,
                        4001L,
                        null,
                        null,
                        "java",
                        "sample code",
                        LocalDateTime.parse("2026-09-28T10:00:00")
                    )
                );
        ReflectionTestUtils.setField(submission, "id", 2002L);
        ReflectionTestUtils.setField(submission, "status", status);
        ReflectionTestUtils.setField(submission, "verdict", JudgeVerdict.AC);
        ReflectionTestUtils.setField(submission, "passedCount", 12);
        ReflectionTestUtils.setField(submission, "totalCount", 12);
        ReflectionTestUtils.setField(submission, "maxTimeMs", 12);
        ReflectionTestUtils.setField(submission, "maxMemoryKb", 8192);
        return submission;
    }
}
