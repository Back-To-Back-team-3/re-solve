package com.backtoback.problem.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** DB 제약 위반·잠금 경합 응답과 상세 오류를 노출하지 않는 로그 정책을 확인한다. */
@ExtendWith(OutputCaptureExtension.class)
class ProblemExceptionHandlerTest {
    @Test
    @DisplayName("DB 잠금 대기 실패는 버전 충돌 오류로 응답하고 상세 오류를 로그에 남기지 않는다")
    void lockAcquisitionFailureIsVersionConflict(CapturedOutput output) {
        var exception = new CannotAcquireLockException("internal-lock-detail");

        var response = new ProblemExceptionHandler().handlePessimisticLock(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code()).isEqualTo("PROBLEM_VERSION_CONFLICT");
        assertThat(output.getAll()).contains("CannotAcquireLockException").doesNotContain("internal-lock-detail");
    }

    @ParameterizedTest
    @DisplayName("태그 중복·사용 중 오류에 해당하지 않는 제약 위반은 서버 오류로 응답하고 로그에 오류 코드와 예외 타입만 남긴다")
    @CsvSource(
        value = {
            "1062, uk_other_name",
            "1062, uk_tags_display_order",
            "1451, fk_other_parent",
            "1452, fk_problem_revision_tags_tag",
            "1048, uk_tags_name",
            "1062, NULL"
        },
        nullValues = "NULL"
    )
    void unrelatedIntegrityViolationsRemainServerErrors(int mysqlError, String constraintName, CapturedOutput output) {
        // given
        var sqlException = new SQLException("internal-db-detail", "23000", mysqlError);
        var violation = new ConstraintViolationException("constraint failed", sqlException, "sql", constraintName);
        var exception = new DataIntegrityViolationException("DB write failed", violation);

        // when
        var response = new ProblemExceptionHandler().handleDataIntegrity(exception);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("서버 오류가 발생했습니다.");
        assertThat(output.getAll())
            .contains("COMMON_INTERNAL_SERVER_ERROR", "DataIntegrityViolationException")
            .doesNotContain("internal-db-detail", "constraint failed", "DB write failed");
    }
}
