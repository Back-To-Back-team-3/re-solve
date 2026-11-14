package com.backtoback.common.api;

import java.util.List;

import com.backtoback.common.exception.ErrorCode;

/**
 * 공통 코드 또는 서비스별 오류 코드를 동일한 실패 응답으로 변환한다.
 * 내부 API도 이 오류 형식을 사용하며, HTTP 상태 설정은 웹 처리기가 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 오류 본문과 필드별 검증 사유</li>
 * </ul>
 *
 * @param success 처리 성공 여부; 팩토리 메서드는 false로 설정
 * @param error 오류 코드, 메시지와 검증 사유
 */
public record ApiErrorResponse(boolean success, ErrorBody error) {
    /**
     * 기본 안내 메시지와 빈 검증 사유 목록으로 오류 응답을 생성한다.
     *
     * @param code 공통 또는 도메인 오류 코드
     * @return details가 빈 배열로 표현되는 실패 응답
     */
    public static ApiErrorResponse of(ErrorCode code) {
        return of(code, code.getMessage(), List.of());
    }

    /**
     * 기본 안내 메시지에 필드별 검증 사유를 연결한다.
     *
     * @param code 공통 또는 도메인 오류 코드
     * @param details 필드별 검증 사유; 없으면 빈 목록
     * @return 코드에 정의된 메시지를 사용하는 실패 응답
     */
    public static ApiErrorResponse of(ErrorCode code, List<FieldError> details) {
        return of(code, code.getMessage(), details);
    }

    /**
     * 오류 코드와 호출자가 정한 안전한 안내 메시지로 응답한다.
     * 예외의 내부 메시지나 요청 원문을 안내 메시지로 전달하지 않는다.
     *
     * @param code 공통 또는 도메인 오류 코드
     * @param message 사용자에게 공개할 안내 메시지
     * @param details 필드별 검증 사유; 없으면 빈 목록
     * @return success가 false인 응답
     */
    public static ApiErrorResponse of(ErrorCode code, String message, List<FieldError> details) {
        return new ApiErrorResponse(false, new ErrorBody(code.getCode(), message, details));
    }

    /**
     * 클라이언트가 code로 분기할 수 있는 오류 정보를 전달한다.
     *
     * @param code 문서에 정의된 오류 식별 문자열
     * @param message 사용자 안내 메시지
     * @param details 필드별 검증 사유 목록
     */
    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    /**
     * 검증에 실패한 필드와 사용자에게 공개할 사유를 전달한다.
     *
     * @param field 검증 대상 필드 이름
     * @param reason 검증 실패 사유; 거절된 입력값은 포함하지 않음
     */
    public record FieldError(String field, String reason) {
    }
}
