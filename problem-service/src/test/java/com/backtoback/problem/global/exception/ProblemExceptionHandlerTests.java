package com.backtoback.problem.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(OutputCaptureExtension.class)
class ProblemExceptionHandlerTests {
    /**
     * 다른 제약 위반이나 자식 FK 입력 오류를 태그 중복·사용 중 오류로 잘못 응답하지 않는지 검증한다.
     * 실제 DB 오류는 로그에만 기록한다.
     *
     * @param mysqlError MySQL 오류 코드
     * @param constraintName 위반한 DB 제약 이름
     * @param output 테스트 실행 중 캡처한 로그 출력
     */
    @ParameterizedTest
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
        var sqlException = new SQLException("internal-db-detail", "23000", mysqlError);
        var violation = new ConstraintViolationException("constraint failed", sqlException, "sql", constraintName);
        var exception = new DataIntegrityViolationException("DB write failed", violation);

        var response = new ProblemExceptionHandler().handleDataIntegrity(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("COMMON_INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().error().message()).isEqualTo("서버 오류가 발생했습니다.");
        assertThat(output.getAll()).contains("internal-db-detail", "DataIntegrityViolationException");
    }
}
