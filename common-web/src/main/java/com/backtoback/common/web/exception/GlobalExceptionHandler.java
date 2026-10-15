package com.backtoback.common.web.exception;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;

/**
 * Spring MVC 컨트롤러에서 발생한 예외를 공통 오류 응답으로 변환한다.
 * 서비스별 비즈니스 예외는 지정된 오류 코드를 사용하고, Spring 표준 예외는 상태와 응답 헤더를 유지한다.
 * 서버 오류는 예외 메시지를 제외하고 타입과 발생 위치를 로그에 기록한다.
 * 사용하는 서비스에서 컴포넌트 스캔 또는 명시적 import로 등록해야 하며, 필터 예외는 처리하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 내부·외부 API의 오류 본문과 검증 사유</li>
 * <li>API 명세서 v1.1 / §0.4 에러 코드 표: 잘못된 요청과 서버 오류의 공통 코드</li>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: Gateway가 전달한 사용자 식별 헤더의 인증 오류 구분</li>
 * <li>정책 및 상태 v1.0 / §9.4 운영·보안 정책: 요청 본문·토큰·개인정보의 로그 미기록</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Gateway가 인증 후 전달하는 사용자 식별 헤더
    private static final String USER_ID_HEADER = "X-User-Id";

    // 순환하거나 과도하게 긴 예외 원인 체인의 로그 탐색 상한
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * 서비스에서 정의한 비즈니스 오류의 상태·기본 안내와 부가 응답 헤더를 전달한다.
     * 서버 오류에 해당하는 비즈니스 예외만 메시지를 제외하고 진단 로그에 기록한다.
     *
     * @param exception 공통 또는 서비스별 오류 코드를 가진 예외
     * @return 오류 코드에 지정된 HTTP 상태와 공통 오류 본문
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.getErrorCode();
        if (code.getStatus().is5xxServerError()) {
            logServerError(exception);
        }
        return ResponseEntity
            .status(code.getStatus())
            .headers(exception.getResponseHeaders())
            .body(ApiErrorResponse.of(code));
    }

    /**
     * 쿼리·경로·헤더 등 메서드 인자의 검증 실패를 항목별 사유로 변환한다.
     * 반환값 검증 실패는 서버 오류로 처리하고 내부 검증 사유는 응답에 노출하지 않는다.
     *
     * @param exception 메서드 인자 또는 반환값의 검증 결과
     * @return 입력 실패는 400과 검증 사유, 반환값 실패는 상세 사유 없는 500 응답
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleParameterValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            logServerError(exception);
            return ResponseEntity
                .status(exception.getStatusCode())
                .headers(exception.getHeaders())
                .body(ApiErrorResponse.of(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR));
        }
        LOG.warn("요청 파라미터 검증 실패: exceptionType={}", exception.getClass().getSimpleName());
        List<ApiErrorResponse.FieldError> details = new ArrayList<>();
        for (var result : exception.getParameterValidationResults()) {
            String parameterName = requestParameterName(result.getMethodParameter());
            for (var error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : parameterName;
                details.add(new ApiErrorResponse.FieldError(field, error.getDefaultMessage()));
            }
        }
        for (var error : exception.getCrossParameterValidationResults()) {
            details.add(new ApiErrorResponse.FieldError("request", error.getDefaultMessage()));
        }
        return ResponseEntity
            .status(exception.getStatusCode())
            .headers(exception.getHeaders())
            .body(ApiErrorResponse.of(CommonErrorCode.COMMON_INVALID_REQUEST, "입력값을 확인해 주세요.", details));
    }

    /**
     * 필수 사용자 식별 헤더 누락은 인증 실패로 구분하고 나머지 헤더 누락은 입력 오류로 처리한다.
     *
     * @param exception 누락되었거나 타입 변환 후 값이 없는 필수 헤더
     * @return X-User-Id는 401, 나머지는 Spring이 지정한 상태와 헤더를 유지하는 응답
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        if (USER_ID_HEADER.equalsIgnoreCase(exception.getHeaderName())) {
            return ResponseEntity
                .status(CommonErrorCode.AUTH_TOKEN_INVALID.getStatus())
                .body(ApiErrorResponse.of(CommonErrorCode.AUTH_TOKEN_INVALID));
        }
        return ResponseEntity
            .status(exception.getStatusCode())
            .headers(exception.getHeaders())
            .body(
                ApiErrorResponse
                    .of(
                        CommonErrorCode.COMMON_INVALID_REQUEST,
                        List.of(new ApiErrorResponse.FieldError(exception.getHeaderName(), "필수 값입니다."))
                    )
            );
    }

    /**
     * 요청 객체의 검증 실패를 필드별 사유로 변환한다.
     * 거절된 입력값과 예외 메시지는 로그나 오류 본문에 직접 포함하지 않는다.
     *
     * @param exception 필드별 검증 결과를 가진 예외
     * @return 검증 안내와 details를 포함하며 Spring이 지정한 상태와 헤더를 유지하는 응답
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        LOG.warn("요청 객체 검증 실패: exceptionType={}", exception.getClass().getSimpleName());
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
            .body(ApiErrorResponse.of(CommonErrorCode.COMMON_INVALID_REQUEST, "입력값을 확인해 주세요.", details));
    }

    /**
     * 요청값의 타입 불일치나 읽을 수 없는 요청 본문을 잘못된 요청으로 처리한다.
     * 원문이 포함될 수 있는 예외 메시지 대신 예외 종류만 기록한다.
     *
     * @param exception 타입 변환 또는 요청 본문 읽기 실패
     * @return X-User-Id 헤더의 타입 불일치는 401, 그 외에는 400 공통 요청 오류 응답
     */
    @ExceptionHandler(
        {
            TypeMismatchException.class,
            HttpMessageNotReadableException.class
        }
    )
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        LOG.warn("잘못된 요청 형식: exceptionType={}", exception.getClass().getSimpleName());
        if (exception instanceof MethodArgumentTypeMismatchException mismatch
            && mismatch.getParameter().hasParameterAnnotation(RequestHeader.class)
            && USER_ID_HEADER.equalsIgnoreCase(requestParameterName(mismatch.getParameter()))) {
            return ResponseEntity
                .status(CommonErrorCode.AUTH_TOKEN_INVALID.getStatus())
                .body(ApiErrorResponse.of(CommonErrorCode.AUTH_TOKEN_INVALID));
        }
        return ResponseEntity
            .status(CommonErrorCode.COMMON_INVALID_REQUEST.getStatus())
            .body(ApiErrorResponse.of(CommonErrorCode.COMMON_INVALID_REQUEST));
    }

    /**
     * 지원하지 않는 서버 변환 설정을 클라이언트 입력 오류와 구분한다.
     * TypeMismatchException의 하위 예외지만 서버 설정 문제이므로 500으로 처리한다.
     *
     * @param exception 서버에 필요한 타입 변환 기능이 없는 예외
     * @return 내부 설정을 노출하지 않는 500 공통 서버 오류 응답
     */
    @ExceptionHandler(ConversionNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleConversionNotSupported(ConversionNotSupportedException exception) {
        logServerError(exception);
        return ResponseEntity
            .status(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR.getStatus())
            .body(ApiErrorResponse.of(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR));
    }

    /**
     * 나머지 Spring 표준 예외의 상태와 Allow 등 응답 헤더를 보존한다.
     * Spring이 상태를 지정하지 않은 예외는 500으로 처리하며 서버 오류는 메시지를 제외하고 기록한다.
     *
     * @param exception 개별 처리기에 매핑되지 않은 예외
     * @return Spring 지정 상태 또는 500 상태의 공통 오류 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            boolean serverError = error.getStatusCode().is5xxServerError();
            if (serverError) {
                logServerError(exception);
            } else {
                LOG.warn("요청 처리 거절: exceptionType={}", exception.getClass().getSimpleName());
            }
            ErrorCode code
                = serverError ? CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR : CommonErrorCode.COMMON_INVALID_REQUEST;
            return ResponseEntity
                .status(error.getStatusCode())
                .headers(error.getHeaders())
                .body(ApiErrorResponse.of(code));
        }
        logServerError(exception);
        return ResponseEntity
            .status(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR.getStatus())
            .body(ApiErrorResponse.of(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR));
    }

    /**
     * 명시된 HTTP 입력 이름을 우선하고 이름이 없으면 Java 파라미터명 또는 인덱스를 사용한다.
     * 어노테이션의 name과 value 중 어느 쪽을 지정해도 같은 이름으로 오류를 전달한다.
     *
     * @param parameter 검증 또는 타입 변환에 실패한 메서드 파라미터
     * @return 쿼리·경로·헤더 이름 또는 입력값을 포함하지 않는 대체 이름
     */
    private String requestParameterName(MethodParameter parameter) {
        var header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return inputName(header.name(), header.value(), parameter);
        }
        var query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            return inputName(query.name(), query.value(), parameter);
        }
        var path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return inputName(path.name(), path.value(), parameter);
        }
        return inputName("", "", parameter);
    }

    /**
     * HTTP 입력 이름의 두 별칭과 파라미터 메타데이터에서 사용할 이름을 고른다.
     *
     * @param name 어노테이션의 명시적 name
     * @param value 어노테이션의 value 별칭
     * @param parameter 이름이 생략된 경우 참조할 메서드 파라미터
     * @return 비어 있지 않은 입력 이름; 메타데이터도 없으면 arg와 인덱스의 조합
     */
    private String inputName(String name, String value, MethodParameter parameter) {
        if (StringUtils.hasText(name)) {
            return name;
        }
        if (StringUtils.hasText(value)) {
            return value;
        }
        String parameterName = parameter.getParameterName();
        return parameterName != null ? parameterName : "arg" + parameter.getParameterIndex();
    }

    /**
     * 서버 예외와 최대 10개 원인 단계의 타입·발생 위치를 한 로그에 기록한다.
     * 예외 객체와 메시지·검증값은 로거에 전달하지 않으며 suppressed 예외는 탐색하지 않는다.
     *
     * @param exception 진단할 서버 예외
     */
    private void logServerError(Exception exception) {
        StringBuilder diagnostic = new StringBuilder();
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            diagnostic
                .append(cause.getClass().getName())
                .append(": ")
                .append(Arrays.toString(cause.getStackTrace()))
                .append(System.lineSeparator());
            cause = cause.getCause();
        }
        LOG.error("서버 오류 발생 (예외 타입·발생 위치): {}", diagnostic);
    }
}
