package com.backtoback.problem.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.backtoback.problem.global.api.ApiErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // @Valid 요청 본문의 검증 실패: 400 응답에 필드별 오류를 포함한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        // 검증 예외에는 입력값이 포함될 수 있으므로 예외 종류만 기록한다.
        LOG.warn("Request validation failed: exceptionType={}", exception.getClass().getSimpleName());
        var details
            = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity
            .status(exception.getStatusCode())
            .headers(exception.getHeaders())
            .body(ApiErrorResponse.of(ErrorCode.COMMON_INVALID_REQUEST, "입력값을 확인해 주세요.", details));
    }

    // 숫자 자리에 문자 입력, 잘못된 JSON 등 요청값을 읽거나 변환할 수 없는 경우 400으로 응답한다.
    @ExceptionHandler(
        {
            TypeMismatchException.class,
            HttpMessageNotReadableException.class
        }
    )
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        // 파싱 예외에는 요청 본문이 포함될 수 있으므로 예외 종류만 기록한다.
        LOG.warn("Invalid request: exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity
            .status(ErrorCode.COMMON_INVALID_REQUEST.getStatus())
            .body(ApiErrorResponse.of(ErrorCode.COMMON_INVALID_REQUEST));
    }

    // 필요한 타입 변환기가 없는 서버 설정 문제: 일반 입력 타입 오류(400)와 구분하여 500으로 응답한다.
    @ExceptionHandler(ConversionNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleConversionNotSupported(ConversionNotSupportedException exception) {
        LOG.error("Request conversion failed", exception);
        return ResponseEntity
            .status(ErrorCode.COMMON_INTERNAL_SERVER_ERROR.getStatus())
            .body(ApiErrorResponse.of(ErrorCode.COMMON_INTERNAL_SERVER_ERROR));
    }

    // 나머지 예외: Spring이 지정한 상태 코드와 헤더가 있으면 유지하고, 그 외에는 500으로 응답한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        // Spring 표준 예외에 지정된 상태 코드와 Allow 등 응답 헤더를 유지한다.
        if (exception instanceof ErrorResponse error) {
            boolean serverError = error.getStatusCode().is5xxServerError();
            if (serverError) {
                LOG.error("Request failed", exception);
            } else {
                LOG.warn("Request rejected: exceptionType={}", exception.getClass().getSimpleName());
            }
            ErrorCode code = serverError ? ErrorCode.COMMON_INTERNAL_SERVER_ERROR : ErrorCode.COMMON_INVALID_REQUEST;
            return ResponseEntity
                .status(error.getStatusCode())
                .headers(error.getHeaders())
                .body(ApiErrorResponse.of(code));
        }
        LOG.error("Request failed", exception);
        return ResponseEntity
            .status(ErrorCode.COMMON_INTERNAL_SERVER_ERROR.getStatus())
            .body(ApiErrorResponse.of(ErrorCode.COMMON_INTERNAL_SERVER_ERROR));
    }
}
