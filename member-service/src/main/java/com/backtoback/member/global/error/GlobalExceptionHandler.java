package com.backtoback.member.global.error;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.backtoback.member.global.response.ErrorResponse;
import com.backtoback.member.global.response.ErrorResponse.FieldErrorDetail;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String USER_ID_HEADER = "X-User-Id";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.warn("Business exception: {}", errorCode, exception);
        }
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        List<FieldErrorDetail> details
            = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return invalidRequest(details);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
        return invalidRequest(List.of(new FieldErrorDetail(exception.getParameterName(), "필수 값입니다.")));
    }

    /**
     * Gateway가 인증 후 넣어 주는 {@code X-User-Id}가 없으면 인증되지 않은 요청이다.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        if (USER_ID_HEADER.equalsIgnoreCase(exception.getHeaderName())) {
            ErrorCode errorCode = ErrorCode.AUTH_TOKEN_INVALID;
            return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
        }
        return invalidRequest(List.of(new FieldErrorDetail(exception.getHeaderName(), "필수 값입니다.")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return invalidRequest(List.of());
    }

    /**
     * 경로 변수·파라미터·헤더의 타입 변환 실패. 숫자가 아닌 {@code X-User-Id}는 인증 정보가 잘못된 요청으로 본다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        if (USER_ID_HEADER.equalsIgnoreCase(exception.getName())) {
            ErrorCode errorCode = ErrorCode.AUTH_TOKEN_INVALID;
            return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
        }
        return invalidRequest(List.of(new FieldErrorDetail(exception.getName(), "형식이 올바르지 않습니다.")));
    }

    /**
     * Spring MVC가 상태 코드를 정해 둔 예외(404 경로 없음, 405 메서드 불일치, 415 Content-Type 등)는 그 상태를 유지한다.
     * 이런 요청은 서버 오류가 아니므로 500으로 바꾸거나 에러 로그를 남기지 않는다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse springError
            && springError.getStatusCode().is4xxClientError()) {
            return ResponseEntity
                .status(springError.getStatusCode())
                .body(ErrorResponse.of(ErrorCode.COMMON_INVALID_REQUEST));
        }
        log.error("Unexpected exception", exception);
        ErrorCode errorCode = ErrorCode.COMMON_INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    private ResponseEntity<ErrorResponse> invalidRequest(List<FieldErrorDetail> details) {
        ErrorCode errorCode = ErrorCode.COMMON_INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode, details));
    }
}
