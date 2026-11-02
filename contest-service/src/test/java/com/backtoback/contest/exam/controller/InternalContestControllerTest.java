package com.backtoback.contest.exam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.service.InternalContestService;
import com.backtoback.contest.exam.service.InternalContestStubService;
import com.backtoback.contest.global.config.SecurityConfiguration;

import tools.jackson.databind.ObjectMapper;

/**
 * 동일한 Stub 응답을 Service mock에 연결해 11경로의 입력·직렬화·상태·헤더·공통 오류를 확인한다.
 * MVC slice는 JPA 감사 설정을 포함한 CommonConfiguration을 스캔하지 않고 기존 보안 체인을 직접 사용한다.
 */
@WebMvcTest(InternalContestController.class)
@ContextConfiguration(
    classes = {
        InternalContestController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
@ActiveProfiles(
    {
        "local",
        "internal-stub"
    }
)
@TestPropertySource(properties = "contest.internal-http.mode=stub")
class InternalContestControllerTest {
    private static final String BASE = "/internal/v1/contest";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InternalContestService service;

    @BeforeEach
    void connectFixtureResponses() {
        var stub = new InternalContestStubService();
        given(service.validateContext(any())).willAnswer(call -> stub.validateContext(call.getArgument(0)));
        given(service.getActiveParticipations(anyString()))
            .willAnswer(call -> stub.getActiveParticipations(call.getArgument(0)));
        given(service.prepareGuard(anyString(), anyString(), any()))
            .willAnswer(call -> stub.prepareGuard(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        given(service.completeGuard(anyString(), anyString(), any()))
            .willAnswer(call -> stub.completeGuard(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        given(service.cancelGuard(anyString(), anyString(), any()))
            .willAnswer(call -> stub.cancelGuard(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        given(service.getStudyExams(anyString(), nullable(String.class), anyInt()))
            .willAnswer(call -> stub.getStudyExams(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        given(service.getProblemUsages(anyString())).willAnswer(call -> stub.getProblemUsages(call.getArgument(0)));
        given(service.getHintBlocks(anyString(), nullable(String.class), anyInt()))
            .willAnswer(call -> stub.getHintBlocks(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        given(service.getShareableSubmission(anyString(), anyString(), anyString()))
            .willAnswer(
                call -> stub.getShareableSubmission(call.getArgument(0), call.getArgument(1), call.getArgument(2))
            );
        given(
            service
                .getFinalizedResult(anyString(), anyString(), nullable(String.class), nullable(String.class), anyInt())
        )
            .willAnswer(
                call -> stub
                    .getFinalizedResult(
                        call.getArgument(0),
                        call.getArgument(1),
                        call.getArgument(2),
                        call.getArgument(3),
                        call.getArgument(4)
                    )
            );
        given(service.getRejudgeEligibility(anyString()))
            .willAnswer(call -> stub.getRejudgeEligibility(call.getArgument(0)));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,55,SAMPLES_ONLY",
            "CONTEST,75,FULL_VERDICT"
        }
    )
    @DisplayName("문맥 검증은 인증 헤더 없이 직접 데이터 객체와 문자열 ID·서울 시각을 반환한다")
    void returnsContextWithoutWrapper(String context, String id, String visibility) throws Exception {
        // given
        var request = new ContextValidateRequest(context, id, "7", "101", "1001", "RUN", null);

        // when & then
        mvc
            .perform(
                post(BASE + "/contexts/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Role", "INVALID")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.success").doesNotExist())
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.participantId").value(id + "7"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.resultVisibility").value(visibility))
            .andExpect(jsonPath("$.serverNow").value("2026-10-20T20:15:00+09:00"))
            .andExpect(jsonPath("$.receiptPresentForSubmission").doesNotExist());
        verify(service).validateContext(request);
    }

    @Test
    @DisplayName("참가·시험·사용·힌트 조회는 각 경로와 기본 페이지 크기를 유지한다")
    void returnsQueryResponses() throws Exception {
        // given
        // when & then
        mvc
            .perform(get(BASE + "/members/7/active-participations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.withdrawalBlocked").value(true))
            .andExpect(jsonPath("$.items[0].contextId").isString())
            .andExpect(jsonPath("$.items[0].participantId").isString())
            .andExpect(jsonPath("$.items[0].enteredAt").isString())
            .andExpect(jsonPath("$.items[0].status").value("STARTED"));
        mvc
            .perform(get(BASE + "/studies/12/exams"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].examId").value("51"))
            .andExpect(jsonPath("$.items[0].examRevision").value("1"))
            .andExpect(jsonPath("$.items[0].startsAt").isString())
            .andExpect(jsonPath("$.items[0].endsAt").isString())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextCursor").value((Object)null));
        mvc
            .perform(get(BASE + "/problems/101/usages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasBlockingUsage").value(true))
            .andExpect(jsonPath("$.items[0].problemRevisionId").value("1001"));
        mvc
            .perform(get(BASE + "/hint-blocks").param("memberId", "8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].memberId").value("8"))
            .andExpect(jsonPath("$.items[0].participantRevision").value("1"))
            .andExpect(jsonPath("$.items[0].contextRevision").value("1"))
            .andExpect(jsonPath("$.items[0].problemIds[0]").value("101"));
        verify(service).getStudyExams("12", null, 20);
        verify(service).getHintBlocks("8", null, 20);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "/members/999/active-participations",
            "/studies/999/exams",
            "/problems/999/usages",
            "/hint-blocks?memberId=999",
            "/results/EXAM/59",
            "/results/CONTEST/80"
        }
    )
    @DisplayName("대상이 없는 목록과 참가자가 없는 확정 결과는 200과 빈 배열을 반환한다")
    void returnsEmptyLists(String path) throws Exception {
        // given
        // when & then
        mvc.perform(get(BASE + path)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "MEMBER,8,WITHDRAWAL,200,true",
            "STUDY,12,STUDY_CLOSE,202,false",
            "STUDY,13,STUDY_CLOSE,200,true",
            "PROBLEM,103,PROBLEM_HIDE,200,true",
            "PROBLEM,105,PROBLEM_HIDE,200,false"
        }
    )
    @DisplayName("Guard 준비는 스터디 진행 대기만 202이고 종결·준비 응답은 200이다")
    void returnsGuardPreparation(String type, String id, String operationType, int httpStatus, boolean ready)
        throws Exception {
        // given
        var request
            = Map
                .of(
                    "operationId",
                    "stub-" + type.toLowerCase(java.util.Locale.ROOT) + "-" + id,
                    "operationType",
                    operationType,
                    "expectedSourceVersion",
                    "10"
                );

        // when & then
        mvc
            .perform(
                post(BASE + "/guards/{type}/{id}/prepare", type, id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.operationId").value(request.get("operationId")))
            .andExpect(jsonPath("$.ready").value(ready))
            .andExpect(jsonPath("$.state").isString())
            .andExpect(jsonPath("$.operationResult").isString())
            .andExpect(jsonPath("$.preparedAt").isString())
            .andExpect(jsonPath("$.canceledExamIds").isArray())
            .andExpect(jsonPath("$.blockingExamIds").isArray());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "complete,103,11,BLOCKED,COMMITTED",
            "cancel,103,10,OPEN,ABORTED",
            "cancel,104,10,BLOCKED,ABORTED"
        }
    )
    @DisplayName("Guard 완료·취소 경로는 종결 결과와 문자열 원천 버전을 반환한다")
    void returnsGuardCompletion(String action, String id, String version, String state, String result)
        throws Exception {
        // given
        var request = Map.of("operationId", "stub-problem-" + id, "sourceVersion", version, "reason", "개발 검증");

        // when & then
        mvc
            .perform(
                post(BASE + "/guards/PROBLEM/{id}/{action}", id, action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operationId").value("stub-problem-" + id))
            .andExpect(jsonPath("$.state").value(state))
            .andExpect(jsonPath("$.operationResult").value(result))
            .andExpect(jsonPath("$.sourceVersion").value(version));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,5701,57",
            "CONTEST,7701,77"
        }
    )
    @DisplayName("공유 응답은 직접 데이터와 불변 코드 필드를 반환하고 캐시를 금지한다")
    void returnsShareableSnapshot(String context, String receipt, String id) throws Exception {
        // given
        // when & then
        mvc
            .perform(get(BASE + "/submissions/{context}/{receipt}/shareable", context, receipt).param("memberId", "7"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(jsonPath("$.context").value(context))
            .andExpect(jsonPath("$.contextId").value(id))
            .andExpect(jsonPath("$.receiptId").value(receipt))
            .andExpect(jsonPath("$.submissionId").isString())
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.problemId").value("101"))
            .andExpect(jsonPath("$.problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.language").value("java"))
            .andExpect(jsonPath("$.sourceCode").isString())
            .andExpect(jsonPath("$.verdict").value("AC"))
            .andExpect(jsonPath("$.resultRevision").value("1"));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "EXAM,57",
            "CONTEST,77"
        }
    )
    @DisplayName("확정 결과는 숫자·문자열·boolean·명시적 null 필드를 문맥별로 직렬화한다")
    void returnsFinalizedSnapshot(String context, String id) throws Exception {
        // given
        // when
        var response
            = mvc
                .perform(get(BASE + "/results/{context}/{id}", context, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value(context))
                .andExpect(jsonPath("$.contextId").value(id))
                .andExpect(jsonPath("$.resultStatus").value("FINALIZED"))
                .andExpect(jsonPath("$.resultRevision").value("1"))
                .andExpect(jsonPath("$.finalizedAt").isString())
                .andExpect(jsonPath("$.items[0].participantId").isString())
                .andExpect(jsonPath("$.items[0].isRanked").value(true))
                .andExpect(jsonPath("$.items[0].rank").isNumber())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn()
                .getResponse();

        // then
        var root = objectMapper.readTree(response.getContentAsString());
        var item = root.path("items").get(0);
        boolean exam = "EXAM".equals(context);
        assertThat(item.path("score").isString()).isEqualTo(exam);
        assertThat(item.path("score").isNull()).isEqualTo(!exam);
        assertThat(item.path("solvedCount").isNull()).isEqualTo(exam);
        assertThat(item.path("penaltyMinutes").isNull()).isEqualTo(exam);
        var problem = item.path("problems").get(0);
        assertThat(problem.has("isSolved")).isTrue();
        assertThat(problem.path("isSolved").isNull()).isEqualTo(exam);
        assertThat(problem.path("passedCount").isNull()).isEqualTo(!exam);
        assertThat(problem.path("totalCount").isNull()).isEqualTo(!exam);
        assertThat(root.path("problems").get(0).path("score").isNull()).isEqualTo(!exam);
        assertThat(root.has("nextCursor")).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("sourceCode", "email", "hiddenTests");
    }

    @ParameterizedTest
    @CsvSource(
        {
            "51,SCHEDULED,false",
            "55,IN_PROGRESS,true",
            "56,CLOSED,true",
            "57,FINALIZED,false",
            "58,CANCELED,false"
        }
    )
    @DisplayName("기존 재채점 경로는 모든 부모 상태에서 세 필드만 포함한 200 응답을 반환한다")
    void returnsExactRejudgeFields(String id, String parentStatus, boolean eligible) throws Exception {
        // given
        // when
        var response
            = mvc
                .perform(get("/internal/v1/exams/{id}/rejudge-eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examId").value(id))
                .andExpect(jsonPath("$.status").value(parentStatus))
                .andExpect(jsonPath("$.eligible").value(eligible))
                .andReturn()
                .getResponse();

        // then
        assertThat(objectMapper.readTree(response.getContentAsString()).size()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource(
        {
            "/submissions/EXAM/5701/shareable?memberId=8,403,CONTEXT_ACCESS_DENIED",
            "/submissions/CONTEST/7702/shareable?memberId=8,409,SUBMISSION_NOT_SHAREABLE",
            "/submissions/EXAM/5501/shareable?memberId=7,409,SUBMISSION_NOT_SHAREABLE",
            "/results/EXAM/56,409,RESULT_NOT_FINALIZED",
            "/results/EXAM/57?resultRevision=99,404,RESULT_REVISION_NOT_FOUND",
            "/results/CONTEST/999,404,CONTEXT_NOT_FOUND"
        }
    )
    @DisplayName("조회 업무 오류는 명세 상태 코드와 공통 오류 형식을 유지한다")
    void returnsQueryErrors(String path, int httpStatus, String code) throws Exception {
        // given
        // when & then
        mvc
            .perform(get(BASE + path))
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.message").isString())
            .andExpect(jsonPath("$.error.details").isArray());
    }

    @Test
    @DisplayName("문맥 검증의 403·404·409를 기존 보안 체인과 공통 처리기를 통해 응답한다")
    void returnsContextErrors() throws Exception {
        // given
        var scenarios
            = List
                .of(
                    List.of("55", "8", "1001", "403", "CONTEXT_ACCESS_DENIED"),
                    List.of("999", "7", "1001", "404", "CONTEXT_NOT_FOUND"),
                    List.of("55", "7", "999", "404", "PROBLEM_REVISION_NOT_INCLUDED"),
                    List.of("56", "7", "1001", "409", "EXAM_CLOSED")
                );

        // when & then
        for (var scenario : scenarios) {
            var request
                = new ContextValidateRequest(
                    "EXAM",
                    scenario.get(0),
                    scenario.get(1),
                    "101",
                    scenario.get(2),
                    "RUN",
                    null
                );
            mvc
                .perform(
                    post(BASE + "/contexts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().is(Integer.parseInt(scenario.get(3))))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(scenario.get(4)))
                .andExpect(jsonPath("$.error.details").isEmpty());
        }
        mvc
            .perform(get("/internal/v1/exams/999/rejudge-eligibility"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("EXAM_NOT_FOUND"));
    }

    @ParameterizedTest
    @CsvSource(
        {
            "7,WITHDRAWAL,stub-member-7,10,409,WITHDRAWAL_BLOCKED_ACTIVE_EXAM,MEMBER",
            "101,PROBLEM_HIDE,stub-problem-101,10,409,PROBLEM_IN_ACTIVE_EXAM,PROBLEM",
            "103,PROBLEM_HIDE,stub-problem-103,11,422,IDEMPOTENCY_KEY_CONFLICT,PROBLEM",
            "103,PROBLEM_HIDE,another,10,409,CONTEST_OPERATION_BLOCKED,PROBLEM",
            "103,PROBLEM_HIDE,another,9,409,SOURCE_VERSION_CONFLICT,PROBLEM"
        }
    )
    @DisplayName("Guard 준비의 거절·상충·점유·버전 충돌은 공통 오류 본문으로 구분한다")
    void returnsGuardErrors(
        String id,
        String operationType,
        String operationId,
        String version,
        int httpStatus,
        String code,
        String type
    )
        throws Exception {
        // given
        var request
            = Map.of("operationId", operationId, "operationType", operationType, "expectedSourceVersion", version);

        // when & then
        mvc
            .perform(
                post(BASE + "/guards/{type}/{id}/prepare", type, id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "{\"context\":\"PRACTICE\"}",
            "{\"context\":\"EXAM\",\"contextId\":\"55\",\"memberId\":\"7\",\"problemId\":\"101\","
                + "\"problemRevisionId\":\"1001\",\"action\":\"READ_SUBMISSION\"}",
            "{\"context\":\"EXAM\",\"contextId\":\"55\",\"memberId\":\"7\",\"problemId\":\"101\","
                + "\"problemRevisionId\":\"1001\",\"action\":\"INVALID\"}"
        }
    )
    @DisplayName("문맥 필수값·열거형·READ_SUBMISSION의 접수 ID를 검증한다")
    void validatesContextBody(String body) throws Exception {
        // given
        // when & then
        mvc
            .perform(post(BASE + "/contexts/validate").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "/studies/12/exams?size=0",
            "/studies/12/exams?size=101",
            "/studies/12/exams?size=wrong",
            "/studies/12/exams?after=broken",
            "/hint-blocks",
            "/hint-blocks?memberId=7&size=101",
            "/results/EXAM/57?size=0",
            "/results/OTHER/57",
            "/submissions/EXAM/5701/shareable"
        }
    )
    @DisplayName("조회의 필수 파라미터·페이지 범위·타입·커서·문맥을 검증한다")
    void validatesQueryInputs(String path) throws Exception {
        // given
        // when & then
        mvc
            .perform(get(BASE + path))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("힌트 복구 조회의 실제 공백 회원 ID는 서비스 호출 전에 400으로 거절한다")
    void rejectsBlankHintMemberId() throws Exception {
        // given
        var request = get(BASE + "/hint-blocks").param("memberId", " ");

        // when
        var result
            = mvc
                .perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("memberId"))
                .andReturn();

        // then
        assertThat(result.getRequest().getParameter("memberId")).isEqualTo(" ");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Guard는 작업 ID와 사유 길이·원천 버전·자원 유형을 검증한다")
    void validatesGuardInputs() throws Exception {
        // given
        var requests
            = List
                .of(
                    Map
                        .of(
                            "operationId",
                            "x".repeat(101),
                            "operationType",
                            "PROBLEM_HIDE",
                            "expectedSourceVersion",
                            "10"
                        ),
                    Map.of("operationId", "", "operationType", "PROBLEM_HIDE", "expectedSourceVersion", "10"),
                    Map
                        .of(
                            "operationId",
                            "stub-problem-103",
                            "operationType",
                            "INVALID",
                            "expectedSourceVersion",
                            "10"
                        ),
                    Map
                        .of(
                            "operationId",
                            "stub-problem-103",
                            "operationType",
                            "PROBLEM_HIDE",
                            "expectedSourceVersion",
                            "wrong"
                        )
                );

        // when & then
        for (var request : requests) {
            mvc
                .perform(
                    post(BASE + "/guards/PROBLEM/103/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        }
        for (String action : List.of("complete", "cancel")) {
            for (String reason : List.of("", "x".repeat(501))) {
                mvc
                    .perform(
                        post(BASE + "/guards/PROBLEM/103/{action}", action)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper
                                    .writeValueAsString(
                                        Map
                                            .of(
                                                "operationId",
                                                "stub-problem-103",
                                                "sourceVersion",
                                                "10",
                                                "reason",
                                                reason
                                            )
                                    )
                            )
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details").isNotEmpty());
            }
        }
        mvc
            .perform(
                post(BASE + "/guards/OTHER/103/prepare")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"operationId\":\"op\",\"operationType\":\"PROBLEM_HIDE\",\"expectedSourceVersion\":\"10\"}"
                    )
            )
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "complete,103,old,11,OPERATION_NOT_CURRENT",
            "cancel,103,old,10,OPERATION_NOT_CURRENT",
            "complete,103,stub-problem-103,10,SOURCE_VERSION_CONFLICT",
            "cancel,103,stub-problem-103,11,SOURCE_VERSION_CONFLICT",
            "cancel,105,stub-problem-105,10,OPERATION_ALREADY_COMPLETED",
            "complete,106,stub-problem-106,11,OPERATION_ALREADY_COMPLETED"
        }
    )
    @DisplayName("완료·취소의 오래된 작업·버전·반대 종결 오류는 409 공통 응답이다")
    void returnsFinishingErrors(String action, String id, String operationId, String version, String code)
        throws Exception {
        // given
        var body = Map.of("operationId", operationId, "sourceVersion", version, "reason", "개발 검증");

        // when & then
        mvc
            .perform(
                post(BASE + "/guards/PROBLEM/{id}/{action}", id, action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.details").isEmpty());
    }

    @Test
    @DisplayName("종료 공개 일반 열람의 참가 ID와 개인 마감은 생략 없이 null로 직렬화한다")
    void returnsPublicReadNulls() throws Exception {
        // given
        var body = new ContextValidateRequest("CONTEST", "79", "999", "101", "1001", "READ_BODY", null);

        // when
        var response
            = mvc
                .perform(
                    post(BASE + "/contexts/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        // then
        var root = objectMapper.readTree(response.getContentAsString());
        assertThat(root.has("participantId")).isTrue();
        assertThat(root.path("participantId").isNull()).isTrue();
        assertThat(root.has("personalEndsAt")).isTrue();
        assertThat(root.path("personalEndsAt").isNull()).isTrue();
    }
}
