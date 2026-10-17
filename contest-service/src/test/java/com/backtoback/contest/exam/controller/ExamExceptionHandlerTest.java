package com.backtoback.contest.exam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.backtoback.contest.exam.exception.ExamNotFoundException;
import com.backtoback.contest.exam.service.ExamService;
import com.backtoback.contest.global.error.ExamExceptionHandler;
import com.backtoback.contest.global.response.ErrorResponse;

/**
 * 실제 서비스 예외가 MVC 응답으로 연결되는 경계와 로그의 비밀값 제외를 확인한다.
 * DB·외부 호출 없이 서비스를 Mock으로 대체해 HTTP 오류 변환 자체를 검증한다.
 * <p>기준 문서: API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 안전한 실패 구조.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExamExceptionHandlerTest {
    /**
     * 내부 오류의 응답·로그에서 원문 메시지를 제외하되 원인 타입과 발생 위치는 보존하는지 확인한다.
     *
     * @param output 테스트 중 수집한 로그
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("서비스 내부 장애는 안전한 500 응답과 메시지가 제거된 원인 로그를 남긴다")
    void unexpectedServiceFailureReturns500AndSanitizedLog(CapturedOutput output) throws Exception {
        // given
        ExamService service = mock(ExamService.class);
        IllegalStateException failure
            = new IllegalStateException("private-request-value", new IllegalArgumentException("private-cause-value"));
        when(service.getDetail("55", "7")).thenThrow(failure);
        MockMvc mockMvc = controllerWith(service);

        // when & then
        mockMvc
            .perform(get("/api/v1/exams/55").header("X-User-Id", "7"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
            .andExpect(jsonPath("$.error.details").isEmpty());
        assertThat(output.getOut() + output.getErr())
            .contains(
                "시험 API 내부 오류",
                IllegalStateException.class.getName(),
                IllegalArgumentException.class.getName(),
                "unexpectedServiceFailureReturns500AndSanitizedLog"
            )
            .doesNotContain("private-request-value", "private-cause-value");
    }

    /**
     * 도메인 예외의 고정 오류가 공통 처리기에 전달되고 내부 장애 로그로 중복 기록되지 않는지 확인한다.
     *
     * @param output 테스트 중 수집한 로그
     * @throws Exception MVC 요청·검증 실패
     */
    @Test
    @DisplayName("시험 없음 예외는 404로 변환하고 내부 장애 로그를 남기지 않는다")
    void domainFailureReturns404WithoutUnexpectedLog(CapturedOutput output) throws Exception {
        // given
        ExamService service = mock(ExamService.class);
        when(service.getDetail("55", "7")).thenThrow(new ExamNotFoundException());
        MockMvc mockMvc = controllerWith(service);

        // when & then
        mockMvc
            .perform(get("/api/v1/exams/55").header("X-User-Id", "7"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("EXAM_NOT_FOUND"));
        assertThat(output.getOut() + output.getErr()).doesNotContain("시험 API 내부 오류");
    }

    /**
     * 서버의 잘못된 반환값을 클라이언트 입력 오류와 구분하고 진단 로그를 보존하는지 확인한다.
     *
     * @param output 테스트 중 수집한 로그
     */
    @Test
    @DisplayName("반환값 검증 실패는 내부 오류 500과 진단 로그로 처리한다")
    void invalidReturnValueIsInternalFailure(CapturedOutput output) {
        // given
        HandlerMethodValidationException failure = mock(HandlerMethodValidationException.class);
        when(failure.isForReturnValue()).thenReturn(true);
        ExamExceptionHandler handler = new ExamExceptionHandler();

        // when
        org.springframework.http.ResponseEntity<ErrorResponse> response = handler.handleParameterValidation(failure);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(output.getOut() + output.getErr()).contains("시험 API 내부 오류");
    }

    /**
     * 서비스 Mock을 실제 시험 Controller와 공통 처리기에 연결한다. 다른 API나 외부 자원을 등록하지 않는다.
     *
     * @param service 해당 시나리오의 응답·예외가 설정된 서비스
     * @return 시험 HTTP 예외 경로를 실행할 MockMvc
     */
    private MockMvc controllerWith(ExamService service) {
        return MockMvcBuilders
            .standaloneSetup(new ExamController(service))
            .setControllerAdvice(new ExamExceptionHandler())
            .build();
    }
}
