package com.backtoback.problem.global.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;

/**
 * 문제 서비스의 DB 제약 위반과 잠금 경합을 오류 응답으로 변환한다.
 * 업무 예외의 응답 변환은 공통 예외 처리기에 맡긴다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ProblemExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    /**
     * DB 제약 이름과 MySQL 오류 코드로 태그 중복·순번 충돌·사용 중 오류를 구분한다.
     * 알 수 없는 제약 위반은 서버 오류로 응답하고 로그에는 오류 코드와 예외 타입만 남긴다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        ErrorCode code = CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                // MySQL이 반환한 테이블 접두사를 제외하고 제약 이름을 비교한다.
                String name = violation.getConstraintName();
                name = name.substring(name.lastIndexOf('.') + 1);
                if (violation.getErrorCode() == 1062 && "uk_tags_name".equals(name)) {
                    code = ProblemErrorCode.PROBLEM_TAG_DUPLICATED;
                } else if (violation.getErrorCode() == 1062 && "uk_tags_display_order".equals(name)) {
                    code = ProblemErrorCode.PROBLEM_VERSION_CONFLICT;
                } else if (violation.getErrorCode() == 1451 && "fk_problem_revision_tags_tag".equals(name)) {
                    code = ProblemErrorCode.PROBLEM_TAG_IN_USE;
                }
                break;
            }
        }
        if (code == CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR) {
            LOG.error("DB 제약 위반: code={}, exceptionType={}", code.getCode(), exception.getClass().getSimpleName());
        } else {
            LOG.warn("DB 제약 위반: code={}, exceptionType={}", code.getCode(), exception.getClass().getSimpleName());
        }
        return ResponseEntity.status(code.getStatus()).body(ApiErrorResponse.of(code));
    }

    /**
     * 낙관적 락 충돌을 재조회가 필요한 변경 충돌로 응답한다.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException exception) {
        LOG.warn("동시 수정 거부: exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity
            .status(ProblemErrorCode.PROBLEM_VERSION_CONFLICT.getStatus())
            .body(ApiErrorResponse.of(ProblemErrorCode.PROBLEM_VERSION_CONFLICT));
    }

    /**
     * DB 비관적 잠금 대기 실패와 교착 경합을 동시 변경 충돌로 응답한다.
     * 연결 장애나 일반 쿼리 실패는 이 예외 유형에 포함되지 않아 기존 공통 처리를 따른다.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handlePessimisticLock(PessimisticLockingFailureException exception) {
        LOG.warn("DB 잠금 경합 거부: exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity
            .status(ProblemErrorCode.PROBLEM_VERSION_CONFLICT.getStatus())
            .body(ApiErrorResponse.of(ProblemErrorCode.PROBLEM_VERSION_CONFLICT));
    }

}
