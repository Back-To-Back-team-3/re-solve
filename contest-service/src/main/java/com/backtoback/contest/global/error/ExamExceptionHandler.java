package com.backtoback.contest.global.error;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.backtoback.contest.global.response.ErrorResponse;

/**
 * 시험 Controller의 업무 오류와 HTTP 입력 오류를 공통 실패 봉투로 변환한다.
 * 알 수 없는 내부 예외의 메시지·원문 요청·비밀값은 응답으로 전달하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답, 에러 코드 표 / Common · Auth: 실패 구조.
 */
@RestControllerAdvice(basePackages = "com.backtoback.contest.exam.controller")
public class ExamExceptionHandler {
    /**
     * 명세 오류 코드를 해당 HTTP 상태로 반환한다.
     *
     * @param exception 서비스에서 전달한 명세 오류
     * @return 세부 검증 목록이 없는 실패 봉투
     */
    @ExceptionHandler(ExamApiException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(ExamApiException exception) {
        return respond(exception.getErrorCode(), List.of());
    }

    /**
     * 바디의 필드·교차 검증 오류를 field와 reason으로 변환한다.
     *
     * @param exception Bean Validation 실패
     * @return 400과 필드별 오류 목록
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException exception) {
        List<ErrorResponse.FieldError> details
            = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.COMMON_INVALID_REQUEST, details);
    }

    /**
     * 경로·쿼리의 메서드 검증 오류를 입력 실패로 반환한다. 반환값 검증 실패는 내부 오류로 구분한다.
     *
     * @param exception Spring MVC의 메서드 검증 실패
     * @return 입력 실패면 400, 반환값 실패면 500
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            return respond(ErrorCode.COMMON_INTERNAL_SERVER_ERROR, List.of());
        }
        List<ErrorResponse.FieldError> details
            = exception
                .getParameterValidationResults()
                .stream()
                .flatMap(
                    result -> result
                        .getResolvableErrors()
                        .stream()
                        .map(
                            error -> new ErrorResponse.FieldError(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage()
                            )
                        )
                )
                .toList();
        return respond(ErrorCode.COMMON_INVALID_REQUEST, details);
    }

    /**
     * 파싱할 수 없는 JSON·시각·Enum 또는 필수 쿼리 누락을 안전한 입력 오류로 통일한다.
     *
     * @param exception HTTP 변환 또는 필수 입력 오류
     * @return 원문을 포함하지 않는 400 응답
     */
    @ExceptionHandler(
        {
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
        }
    )
    public ResponseEntity<ErrorResponse> handleMalformedInput(Exception exception) {
        return respond(
            ErrorCode.COMMON_INVALID_REQUEST,
            List.of(new ErrorResponse.FieldError("request", "요청 형식과 필수 입력값을 확인해 주세요."))
        );
    }

    /**
     * Gateway 사용자 헤더 누락은 일반 바디 검증 오류와 구분한다.
     *
     * @param exception 누락된 필수 헤더
     * @return 사용자 식별자 누락이면 401, 그 외 입력 오류면 400
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        ErrorCode code
            = "X-User-Id".equals(exception.getHeaderName()) ? ErrorCode.AUTH_TOKEN_INVALID
                : ErrorCode.COMMON_INVALID_REQUEST;
        return respond(code, List.of());
    }

    /**
     * 예상하지 못한 오류의 내부 메시지를 숨긴다.
     *
     * @param exception 처리되지 않은 내부 오류
     * @return 명세의 공통 500 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        return respond(ErrorCode.COMMON_INTERNAL_SERVER_ERROR, List.of());
    }

    /**
     * 코드가 정의한 상태와 공통 실패 봉투를 함께 만든다.
     *
     * @param code 반환할 명세 오류
     * @param details 입력 검증 세부 사항
     * @return HTTP 상태와 일치하는 실패 응답
     */
    private ResponseEntity<ErrorResponse> respond(ErrorCode code, List<ErrorResponse.FieldError> details) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, details));
    }
}
