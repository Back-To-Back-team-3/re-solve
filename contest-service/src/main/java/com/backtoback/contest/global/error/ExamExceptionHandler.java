package com.backtoback.contest.global.error;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.backtoback.contest.global.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 시험 Controller의 업무 오류와 HTTP 입력 오류를 공통 오류 응답 형식으로 변환한다.
 * 알 수 없는 내부 예외의 메시지·원문 요청·비밀값은 응답으로 전달하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답, 에러 코드 표 / Common · Auth: 실패 구조.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.backtoback.contest.exam.controller")
public class ExamExceptionHandler {
    /**
     * 명세 오류 코드를 해당 HTTP 상태로 반환한다.
     *
     * @param exception 서비스에서 전달한 명세 오류
     * @return 세부 검증 목록이 없는 공통 오류 응답 형식
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception) {
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
            logUnexpected(exception);
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
     * 파싱할 수 없는 JSON·시각·Enum, 필수 쿼리 누락, 지원하지 않는 미디어 형식을 입력 오류로 통일한다.
     * 명세의 COMMON_INVALID_REQUEST(400)를 유지하며 프레임워크 오류를 내부 장애로 분류하지 않는다.
     *
     * @param exception HTTP 변환 또는 필수 입력 오류
     * @return 원문을 포함하지 않는 400 응답
     */
    @ExceptionHandler(
        {
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
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
     * 예상하지 못한 오류는 진단 로그를 남기고 응답에서 내부 메시지를 숨긴다.
     *
     * @param exception 처리되지 않은 내부 오류
     * @return 명세의 공통 500 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        logUnexpected(exception);
        return respond(ErrorCode.COMMON_INTERNAL_SERVER_ERROR, List.of());
    }

    /**
     * 예외와 원인 체인의 타입·발생 위치만 기록한다. 메시지·요청 본문·헤더는 기록하지 않는다.
     * 외부 호출 예외의 메시지에 인증 정보가 들어가더라도 로그에 그대로 복사하지 않기 위한 경계다.
     * 순환하거나 과도하게 긴 원인 체인은 최대 10개까지만 탐색한다.
     *
     * @param exception 진단할 예상하지 못한 내부 오류
     */
    private void logUnexpected(Exception exception) {
        StringBuilder diagnostic = new StringBuilder();
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            diagnostic
                .append(cause.getClass().getName())
                .append(": ")
                .append(Arrays.toString(cause.getStackTrace()))
                .append(System.lineSeparator());
            cause = cause.getCause();
        }
        log.error("시험 API 내부 오류 (타입·발생 위치): {}", diagnostic);
    }

    /**
     * 코드가 정의한 상태와 공통 오류 응답을 함께 만든다.
     *
     * @param code 반환할 명세 오류
     * @param details 입력 검증 세부 사항
     * @return HTTP 상태와 일치하는 실패 응답
     */
    private ResponseEntity<ErrorResponse> respond(ErrorCode code, List<ErrorResponse.FieldError> details) {
        return ResponseEntity
            .status(code.getStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse.of(code, details));
    }
}
