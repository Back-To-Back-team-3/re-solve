package com.backtoback.problem.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
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
            .body(ApiErrorResponse.of("COMMON_INVALID_REQUEST", "입력값을 확인해 주세요.", details));
    }

    @ExceptionHandler(
        {
            TypeMismatchException.class,
            HttpMessageNotReadableException.class
        }
    )
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiErrorResponse.of("COMMON_INVALID_REQUEST", "요청을 확인해 주세요."));
    }

    @ExceptionHandler(ConversionNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleConversionNotSupported(ConversionNotSupportedException exception) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of("COMMON_INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        // Spring 표준 예외에 지정된 상태 코드와 Allow 등 응답 헤더를 유지한다.
        if (exception instanceof ErrorResponse error) {
            boolean serverError = error.getStatusCode().is5xxServerError();
            String code = serverError ? "COMMON_INTERNAL_SERVER_ERROR" : "COMMON_INVALID_REQUEST";
            String message = serverError ? "서버 오류가 발생했습니다." : "요청을 확인해 주세요.";
            return ResponseEntity
                .status(error.getStatusCode())
                .headers(error.getHeaders())
                .body(ApiErrorResponse.of(code, message));
        }
        // 예외 메시지와 스택에는 SQL·요청 본문이 포함될 수 있으므로 종류만 기록한다.
        LOG.error("Request failed: exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of("COMMON_INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
