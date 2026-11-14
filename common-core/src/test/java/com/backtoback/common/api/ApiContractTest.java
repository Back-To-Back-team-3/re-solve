package com.backtoback.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;

import tools.jackson.databind.ObjectMapper;

/**
 * 공통 자료형의 JSON을 API 명세서 v1.1 §0.1·§0.3의 필드와 타입에 대조한다.
 * 웹 서버 없이 직렬화하며, 서비스에서 정의한 코드도 공통 예외·응답으로 연결되는지 확인한다.
 */
class ApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * JavaScript의 안전한 정수 범위를 넘는 ID를 문자열로 준비한다.
     * ID의 숫자 변환이나 서비스별 Jackson 설정은 이 테스트의 검증 범위에 포함하지 않는다.
     */
    @Test
    @DisplayName("조회 성공 JSON은 null 메시지와 문자열 ID를 유지한다")
    void serializesQuerySuccessWithNullMessage() {
        // given
        var response = ApiResponse.success("9007199254740993");

        // when
        var json = mapper.readTree(mapper.writeValueAsString(response));

        // then
        assertThat(json).isEqualTo(mapper.readTree("""
            {"success":true,"message":null,"data":"9007199254740993"}
            """));
    }

    /**
     * 전체 7건 중 두 번째 페이지를 사용해 페이지 번호의 보정이나 메타데이터 매핑 누락을 확인한다.
     */
    @Test
    @DisplayName("목록 응답은 0 기반 페이지 정보와 전체 건수의 숫자 타입을 유지한다")
    void serializesPageMetadata() {
        // given
        var page = new PageImpl<>(List.of("101", "102"), PageRequest.of(1, 2), 7);
        var response = ApiResponse.success(PageResponse.from(page));

        // when
        var json = mapper.readTree(mapper.writeValueAsString(response));

        // then
        assertThat(json.get("data")).isEqualTo(mapper.readTree("""
            {"content":["101","102"],"page":1,"size":2,"totalElements":7,"totalPages":4,"hasNext":true}
            """));
    }

    /**
     * 공통 enum 대신 별도 enum을 전달해 확장 지점을 확인한다.
     * HTTP 상태는 코드에서 조회하며, 오류 본문에는 노출하지 않는 조건을 함께 검증한다.
     */
    @Test
    @DisplayName("서비스별 오류 코드도 공통 예외와 동일한 오류 JSON을 사용한다")
    void serializesDomainErrorThroughBusinessException() {
        // given
        var exception = new ExampleBusinessException(ExampleErrorCode.EXAMPLE_NOT_FOUND);

        // when
        var response = ApiErrorResponse.of(exception.getErrorCode());
        var json = mapper.readTree(mapper.writeValueAsString(response));

        // then
        assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json).isEqualTo(mapper.readTree("""
            {"success":false,"error":{"code":"EXAMPLE_NOT_FOUND","message":"대상을 찾을 수 없습니다.","details":[]}}
            """));
    }

    /**
     * 기본 메시지를 요청별 안내로 바꾸는 상황을 재현한다.
     * Bean Validation 실행은 포함하지 않고, 전달받은 오류 상세의 직렬화만 검증한다.
     */
    @Test
    @DisplayName("검증 오류 JSON은 오류 코드와 필드별 사유를 전달한다")
    void serializesValidationDetails() {
        // given
        var details = List.of(new ApiErrorResponse.FieldError("name", "필수 값입니다."));

        // when
        var response = ApiErrorResponse.of(CommonErrorCode.COMMON_INVALID_REQUEST, "입력값을 확인해 주세요.", details);
        var json = mapper.readTree(mapper.writeValueAsString(response));

        // then
        assertThat(json).isEqualTo(mapper.readTree("""
            {"success":false,"error":{"code":"COMMON_INVALID_REQUEST","message":"입력값을 확인해 주세요.",
            "details":[{"field":"name","reason":"필수 값입니다."}]}}
            """));
    }

    /**
     * 공통 모듈을 사용하는 서비스에서 별도의 오류 enum을 정의하는 상황을 재현한다.
     */
    private enum ExampleErrorCode implements ErrorCode {
        // 요청 대상이 존재하지 않는 예시 도메인 오류
        EXAMPLE_NOT_FOUND;

        /**
         * 예시 도메인의 코드 문자열을 반환한다.
         *
         * @return enum 상수 이름
         */
        @Override
        public String getCode() {
            return name();
        }

        /**
         * 대상 미존재에 대응하는 상태를 반환한다.
         *
         * @return 404 상태
         */
        @Override
        public HttpStatus getStatus() {
            return HttpStatus.NOT_FOUND;
        }

        /**
         * 예시 도메인의 기본 안내를 반환한다.
         *
         * @return 대상 미존재 안내
         */
        @Override
        public String getMessage() {
            return "대상을 찾을 수 없습니다.";
        }
    }

    /**
     * 서비스별 전용 예외가 공통 BusinessException을 상속하는 상황을 재현한다.
     */
    private static final class ExampleBusinessException extends BusinessException {
        /**
         * 예시 서비스의 오류 코드를 공통 기반에 전달한다.
         *
         * @param errorCode 예시 도메인 오류 코드
         */
        private ExampleBusinessException(ErrorCode errorCode) {
            super(errorCode);
        }
    }
}
