package com.backtoback.contest.exam.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.backtoback.contest.global.error.ErrorCode;

/**
 * DB·AWS·외부 서비스 없는 개발 프로필에서 실제 MVC 입력·직렬화·예외 처리 연결을 검증한다.
 * 응답 코드·DTO·입력 경계를 확인하며 권한·멱등·저장·최초 입장 유지가 동작한다고 검증하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §0.1~§0.3, §4.1.1~§4.1.3, §4.2.1~§4.2.3: HTTP 계약.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(
    {
        "dev",
        "exam-stub"
    }
)
class ExamApiContractTest {
    // 실행 날짜가 달라져도 미래 시작 조건을 만족시키는 계약 테스트 입력.
    private static final String STARTS_AT = "2099-10-20T20:00:00+09:00";
    private static final String ENDS_AT = "2099-10-20T21:30:00+09:00";
    private static final String PROBLEMS = """
        [{"problemId": "101", "score": "30.00", "displayOrder": 1},
         {"problemId": "102", "score": "30.00", "displayOrder": 2},
         {"problemId": "103", "score": "40.00", "displayOrder": 3}]
        """;

    @Autowired
    private MockMvc mockMvc;

    /**
     * 생성 입력이 문자열 회차·점수와 201 성공 봉투로 연결되는지 확인한다.
     * 마이크로초 표기와 +09:00 시차도 검증해 DB 시간 형태를 그대로 노출하는 일을 막는다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("시험 생성 입력을 검증하고 고정 회차와 문자열 총점을 201로 반환한다")
    void createReturnsFixedRevisionAndStringScore() throws Exception {
        // given
        String body = creationBody(PROBLEMS);

        // when & then
        mockMvc
            .perform(
                authenticated(post("/api/v1/studies/12/exams")).contentType(MediaType.APPLICATION_JSON).content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.examId").value("55"))
            .andExpect(jsonPath("$.data.studyId").value("12"))
            .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
            .andExpect(jsonPath("$.data.examRevision").value("1"))
            .andExpect(jsonPath("$.data.version").value("0"))
            .andExpect(jsonPath("$.data.totalScore").value("100.00"))
            .andExpect(jsonPath("$.data.startsAt").value("2099-10-20T20:00:00.000000+09:00"))
            .andExpect(jsonPath("$.data.problems.length()").value(3))
            .andExpect(jsonPath("$.data.problems[0].problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.data.problems[0].score").value("30.00"));
    }

    /**
     * 허용된 시험 시간 양 끝과 단일 문제의 최소·최대 배점을 실제 MVC 검증 경로로 확인한다.
     *
     * @param endsAt 1분 또는 180분 뒤 종료 시각
     * @param score 단일 문제의 최소 또는 최대 점수
     * @throws Exception MVC 요청·검증 실패
     */
    @ParameterizedTest
    @CsvSource(
        {
            "2099-10-20T20:01:00+09:00,0.01",
            "2099-10-20T23:00:00+09:00,9999.99"
        }
    )
    @DisplayName("시험 시간 1분과 180분 및 단일 문제 배점 경계는 생성 가능하다")
    void creationAcceptsBoundaryDurationAndScore(String endsAt, String score) throws Exception {
        // given
        String problems = "[" + problemBody("101", score, 1) + "]";
        String body = creationBody("경계 시험", "FIXED", STARTS_AT, endsAt, problems);

        // when & then
        mockMvc
            .perform(
                authenticated(post("/api/v1/studies/12/exams")).contentType(MediaType.APPLICATION_JSON).content(body)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.totalScore").value(score));
    }

    /**
     * 목록·상세에서 문제 식별자와 본문을 반환하지 않는 DTO 경계를 확인한다.
     * 상세의 serverNow는 회원 진단 API·시험 공통 규칙과 같은 필드명이다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("시험 목록과 상세에는 문제 정보를 노출하지 않고 미등록 참가를 null로 반환한다")
    void queriesDoNotExposeProblemsOrEnterParticipant() throws Exception {
        // given
        MockHttpServletRequestBuilder list = authenticated(get("/api/v1/studies/12/exams"));
        MockHttpServletRequestBuilder detail = authenticated(get("/api/v1/exams/55"));

        // when & then
        mockMvc
            .perform(list)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value(nullValue()))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.content[0].myParticipantStatus").value(nullValue()))
            .andExpect(jsonPath("$.data.content[0].problems").doesNotExist());
        mockMvc
            .perform(detail)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.myParticipation").value(nullValue()))
            .andExpect(jsonPath("$.data.serverNow").value(endsWith("+09:00")))
            .andExpect(jsonPath("$.data.serverTime").doesNotExist())
            .andExpect(jsonPath("$.data.problems").doesNotExist());
    }

    /**
     * 한 건의 개발 목록에서도 상태 필터·빈 페이지·전체 메타데이터를 구분하는지 확인한다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("상태 필터와 다음 페이지 조회는 빈 목록과 일관된 페이지 메타데이터를 반환한다")
    void listRespectsFilterAndPage() throws Exception {
        // given
        MockHttpServletRequestBuilder filter = authenticated(get("/api/v1/studies/12/exams")).param("status", "CLOSED");
        MockHttpServletRequestBuilder nextPage = authenticated(get("/api/v1/studies/12/exams")).param("page", "1");

        // when & then
        mockMvc
            .perform(filter)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(0))
            .andExpect(jsonPath("$.data.totalElements").value(0))
            .andExpect(jsonPath("$.data.totalPages").value(0));
        mockMvc
            .perform(nextPage)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(0))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    /**
     * 서비스의 신규 여부가 JSON에 새 필드를 만들지 않고 HTTP 201·200으로 연결되는지 확인한다.
     * 실제 중복 등록 검증은 DB 서비스의 후속 테스트로 남긴다.
     *
     * @param scenario 개발 시나리오 헤더
     * @param httpStatus 새 등록 201 또는 기존 참가 200
     * @throws Exception MVC 요청·검증 실패
     */
    @ParameterizedTest
    @CsvSource(
        {
            "SUCCESS,201",
            "EXISTING_PARTICIPANT,200"
        }
    )
    @DisplayName("새 참가 등록은 201이고 기존 활성 참가 반환은 200이다")
    void registerDistinguishesCreatedFromExisting(String scenario, int httpStatus) throws Exception {
        // given
        MockHttpServletRequestBuilder request
            = authenticated(post("/api/v1/exams/55/participants")).header("X-Exam-Stub-Scenario", scenario);

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.data.participantId").value("411"))
            .andExpect(jsonPath("$.data.status").value("REGISTERED"))
            .andExpect(jsonPath("$.data.enteredAt").value(nullValue()))
            .andExpect(jsonPath("$.data.participantRevision").value("1"))
            .andExpect(jsonPath("$.data.created").doesNotExist());
    }

    /**
     * 입장 응답이 최초 시각·개인 마감·문자열 참가 순번을 포함하는지 확인한다.
     * 재입장 시 최초 시각 보존은 무상태 stub에서 검증할 수 없으므로 제외한다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("입장 응답은 STARTED 상태와 입장 시각 및 개인 마감을 반환한다")
    void startReturnsEntryAndDeadline() throws Exception {
        // given
        MockHttpServletRequestBuilder request = authenticated(post("/api/v1/exams/55/start"));

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("STARTED"))
            .andExpect(jsonPath("$.data.enteredAt").isString())
            .andExpect(jsonPath("$.data.personalEndsAt").isString())
            .andExpect(jsonPath("$.data.serverNow").isString())
            .andExpect(jsonPath("$.data.participantRevision").value("2"));
    }

    /**
     * 외부 고정 회차 DTO에서 공개 본문·예제·이미지를 구성하고 입력 JSON 텍스트를 보존하는지 확인한다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("시험 문제는 고정 회차의 공개 본문과 함수 명세 및 공개 실행 예제만 반환한다")
    void problemsReturnFixedPublicContent() throws Exception {
        // given
        MockHttpServletRequestBuilder request = authenticated(get("/api/v1/exams/55/problems"));

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.problems.length()").value(3))
            .andExpect(jsonPath("$.data.problems[0].problemRevisionId").value("1001"))
            .andExpect(jsonPath("$.data.problems[0].functionSpec.parameters.length()").value(2))
            .andExpect(jsonPath("$.data.problems[0].executionLimits.outputLimitKb").value(64))
            .andExpect(jsonPath("$.data.problems[0].sampleTests[0].argumentTexts.a").value("1"))
            .andExpect(jsonPath("$.data.problems[0].sampleTests[0].expectedReturnText").value("3"))
            .andExpect(jsonPath("$.data.problems[0].imageIds[0]").value("701"))
            .andExpect(jsonPath("$.data.problems[0].hiddenTests").doesNotExist())
            .andExpect(jsonPath("$.data.problems[0].answerCode").doesNotExist());
    }

    /**
     * 명세 코드와 HTTP 상태가 실패 봉투에 연결되는지 개발 오류 예시로 검증한다.
     * 특정 API의 실제 업무 발생 조건을 판정한 테스트는 아니다.
     *
     * @param code 개발 헤더로 지정할 명세 오류
     * @throws Exception MVC 요청·검증 실패
     */
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("개발 오류 시나리오는 명세 코드와 HTTP 상태를 공통 실패 봉투로 반환한다")
    void errorScenariosReturnCodeAndStatus(ErrorCode code) throws Exception {
        // given
        MockHttpServletRequestBuilder request
            = authenticated(get("/api/v1/exams/55")).header("X-Exam-Stub-Scenario", code.name());

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().is(code.getStatus()))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code.name()))
            .andExpect(jsonPath("$.error.details.length()").value(0))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 생성 바디의 단일 필드·교차 조건·문자열 타입을 실제 Jackson과 Bean Validation으로 확인한다.
     *
     * @param description 표시할 실패 조건
     * @param body 경계 위반 또는 잘못된 JSON 타입 입력
     * @throws Exception MVC 요청·검증 실패
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreationBodies")
    @DisplayName("잘못된 시험 생성 입력은 공통 400 검증 응답이다")
    void invalidCreationReturnsBadRequest(String description, String body) throws Exception {
        // given
        MockHttpServletRequestBuilder request
            = authenticated(post("/api/v1/studies/12/exams")).contentType(MediaType.APPLICATION_JSON).content(body);

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").isString());
    }

    /**
     * 페이지·크기·정렬·Enum이 서비스 호출 전 입력 오류로 변환되는지 확인한다.
     *
     * @param parameter 쿼리 이름
     * @param value 허용하지 않는 값
     * @throws Exception MVC 요청·검증 실패
     */
    @ParameterizedTest
    @CsvSource(
        {
            "page,-1",
            "size,0",
            "size,101",
            "sort,unknown",
            "status,UNKNOWN"
        }
    )
    @DisplayName("허용 범위를 벗어난 목록 쿼리는 400이다")
    void invalidListQueryReturnsBadRequest(String parameter, String value) throws Exception {
        // given
        MockHttpServletRequestBuilder request = authenticated(get("/api/v1/studies/12/exams")).param(parameter, value);

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 사용자 헤더를 요청 바디로 대신하지 않으며 없는·잘못된 인증 식별자를 구분하는지 확인한다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("사용자 헤더가 없거나 BIGINT 범위 밖이면 401이고 잘못된 경로 ID는 400이다")
    void identifiersSeparateAuthenticationFromRequestErrors() throws Exception {
        // given
        MockHttpServletRequestBuilder missing = get("/api/v1/exams/55");
        MockHttpServletRequestBuilder overflow = get("/api/v1/exams/55").header("X-User-Id", "9223372036854775808");
        MockHttpServletRequestBuilder invalidPath = authenticated(get("/api/v1/exams/not-a-number"));

        // when & then
        mockMvc
            .perform(missing)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
        mockMvc
            .perform(overflow)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
        mockMvc
            .perform(invalidPath)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 개발 성공과 오류 시나리오 외 헤더 값을 업무 성공으로 처리하지 않는지 확인한다.
     *
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("알 수 없는 개발 시나리오는 공통 400 입력 오류다")
    void unknownStubScenarioReturnsBadRequest() throws Exception {
        // given
        MockHttpServletRequestBuilder request
            = authenticated(get("/api/v1/exams/55")).header("X-Exam-Stub-Scenario", "UNKNOWN_SCENARIO");

        // when & then
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 본문 생성의 기본값을 공통 헬퍼로 연결한다. 테스트 본문에는 위반 조건만 드러낸다.
     *
     * @param problems 문제 배열 JSON
     * @return 유효한 기본 설정의 생성 JSON
     */
    private static String creationBody(String problems) {
        return creationBody("모의 코테", "FIXED", STARTS_AT, ENDS_AT, problems);
    }

    /**
     * 다른 제목·모드·시간·문제 수의 입력도 같은 생성 JSON 구성 경로를 사용한다.
     * JSON 리터럴을 준비할 뿐 HTTP 호출·저장을 하지 않는다.
     *
     * @param title 제목
     * @param mode 방식
     * @param startsAt 시작 시각
     * @param endsAt 종료 시각
     * @param problems 문제 배열 JSON
     * @return 생성 요청 JSON
     */
    private static String creationBody(String title, String mode, String startsAt, String endsAt, String problems) {
        return """
            {"title": "%s", "mode": "%s", "startsAt": "%s", "endsAt": "%s", "problems": %s}
            """.formatted(title, mode, startsAt, endsAt, problems);
    }

    /**
     * 같은 생성 목적의 값·시간·목록 경계를 데이터로 준비한다.
     *
     * @return 실패 조건 설명과 실제 입력 JSON
     */
    private static Stream<Arguments> invalidCreationBodies() {
        return Stream
            .of(
                Arguments.of("빈 제목", creationBody(" ", "FIXED", STARTS_AT, ENDS_AT, PROBLEMS)),
                Arguments.of("200자 초과 제목", creationBody("가".repeat(201), "FIXED", STARTS_AT, ENDS_AT, PROBLEMS)),
                Arguments.of("FIXED 외 모드", creationBody("시험", "WINDOW", STARTS_AT, ENDS_AT, PROBLEMS)),
                Arguments.of("1분 미만", creationBody("시험", "FIXED", STARTS_AT, STARTS_AT, PROBLEMS)),
                Arguments.of("180분 초과", creationBody("시험", "FIXED", STARTS_AT, "2099-10-20T23:00:01+09:00", PROBLEMS)),
                Arguments
                    .of(
                        "과거 시작",
                        creationBody("시험", "FIXED", "2000-10-20T20:00:00+09:00", "2000-10-20T21:30:00+09:00", PROBLEMS)
                    ),
                Arguments.of("문제 없음", creationBody("[]")),
                Arguments.of("21개 문제", creationBody(problemArray(21))),
                Arguments.of("null 문제", creationBody("[null]")),
                Arguments.of("중복 문제", creationBody(PROBLEMS.replace("\"102\"", "\"101\""))),
                Arguments.of("중복 표시 순서", creationBody(PROBLEMS.replace("\"displayOrder\": 2", "\"displayOrder\": 1"))),
                Arguments.of("누락된 표시 순서", creationBody(PROBLEMS.replace("\"displayOrder\": 3", "\"displayOrder\": 4"))),
                Arguments.of("배점 0", creationBody(PROBLEMS.replace("30.00", "0.00"))),
                Arguments.of("배점 소수점 한 자리", creationBody(PROBLEMS.replace("30.00", "30.0"))),
                Arguments.of("배점 합계 초과", creationBody(PROBLEMS.replace("30.00", "9999.99"))),
                Arguments.of("숫자 점수 JSON", creationBody(PROBLEMS.replace("\"30.00\"", "30.01"))),
                Arguments.of("숫자 문제 ID JSON", creationBody(PROBLEMS.replace("\"101\"", "101"))),
                Arguments.of("BIGINT 범위 밖 문제 ID", creationBody(PROBLEMS.replace("\"101\"", "\"9223372036854775808\"")))
            );
    }

    /**
     * 개수만 다른 문제 목록을 같은 목적의 JSON 준비 헬퍼로 만든다.
     * 문제 ID와 표시 순서는 1부터 연속이며 각 배점은 1.00이다.
     *
     * @param count 목록에 넣을 문제 수
     * @return 문제 배열 JSON
     */
    private static String problemArray(int count) {
        return java.util.stream.IntStream
            .rangeClosed(1, count)
            .mapToObj(index -> problemBody(Integer.toString(index), "1.00", index))
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * 문제 식별자·점수·순서가 다른 경우에도 같은 필드 형식으로 입력을 준비한다.
     *
     * @param problemId 문제 문자열 ID
     * @param score 점수 문자열
     * @param order 표시 순서
     * @return 한 문제의 입력 JSON
     */
    private static String problemBody(String problemId, String score, int order) {
        return """
            {"problemId": "%s", "score": "%s", "displayOrder": %d}
            """.formatted(problemId, score, order);
    }

    /**
     * 테스트 요청에 Gateway가 전달할 사용자 식별자만 준비한다. 실제 JWT·GitHub 계정을 만들지 않는다.
     *
     * @param request 호출 경로와 메서드가 정해진 요청
     * @return 사용자 7의 헤더를 가진 요청
     */
    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", "7");
    }
}
