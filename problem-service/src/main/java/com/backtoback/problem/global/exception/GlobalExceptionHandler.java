package com.backtoback.problem.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.backtoback.problem.global.api.ApiErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException exception,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        var details
            = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return new ResponseEntity<>(
            ApiErrorResponse.of("COMMON_INVALID_REQUEST", "입력값을 확인해 주세요.", details),
            headers,
            status
        );
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        String code = status.is5xxServerError() ? "COMMON_INTERNAL_SERVER_ERROR" : "COMMON_INVALID_REQUEST";
        String message = status.is5xxServerError() ? "서버 오류가 발생했습니다." : "요청을 확인해 주세요.";
        return new ResponseEntity<>(ApiErrorResponse.of(code, message), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        // 예외 메시지와 스택에는 SQL·요청 본문이 포함될 수 있으므로 종류만 기록한다.
        LOG.error("Request failed: exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of("COMMON_INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
