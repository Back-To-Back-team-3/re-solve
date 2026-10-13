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

    /**
     * 요청 본문의 검증 오류를 필드별 안내와 함께 응답한다.
     *
     * @param exception 요청 처리 중 발생한 예외
     * @return 입력값 검증 실패 응답
     */
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

    /**
     * 읽거나 변환할 수 없는 요청값을 잘못된 요청으로 응답한다.
     *
     * @param exception 요청 처리 중 발생한 예외
     * @return 400 요청 오류 응답
     */
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

    /**
     * 서버의 타입 변환 설정 오류를 기록하고 공통 서버 오류로 응답한다.
     *
     * @param exception 요청 처리 중 발생한 예외
     * @return 500 서버 오류 응답
     */
    @ExceptionHandler(ConversionNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleConversionNotSupported(ConversionNotSupportedException exception) {
        LOG.error("Request conversion failed", exception);
        return ResponseEntity
            .status(ErrorCode.COMMON_INTERNAL_SERVER_ERROR.getStatus())
            .body(ApiErrorResponse.of(ErrorCode.COMMON_INTERNAL_SERVER_ERROR));
    }

    /**
     * 처리되지 않은 예외를 공통 오류 응답으로 변환한다.
     * Spring이 지정한 상태와 헤더는 유지하고, 서버 예외의 상세 내용은 로그에만 기록한다.
     *
     * @param exception 요청 처리 중 발생한 예외
     * @return Spring 지정 상태 또는 500 상태의 오류 응답
     */
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
