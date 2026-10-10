package com.backtoback.contest.global.response;

import java.util.List;

import com.backtoback.contest.global.error.ErrorCode;

/**
 * 성공 데이터와 분리된 외부 API 실패 봉투다. 입력 오류에만 필드별 details를 제공한다.
 * <p>기준 문서: API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: success·error 구조.
 *
 * @param success 항상 false
 * @param error 오류 코드·안내·필드 오류
 */
public record ErrorResponse(boolean success, ErrorBody error) {
    /**
     * 명세 오류와 검증 세부 정보를 공통 실패 봉투로 변환한다.
     *
     * @param code 반환할 오류 코드
     * @param details 검증 오류 목록, 업무 오류는 빈 목록
     * @return 실패 응답
     */
    public static ErrorResponse of(ErrorCode code, List<FieldError> details) {
        return new ErrorResponse(false, new ErrorBody(code.name(), code.getMessage(), details));
    }

    /**
     * 클라이언트 분기용 코드와 사용자 안내를 담는다.
     *
     * @param code 명세 오류 코드
     * @param message 안내 문구
     * @param details 필드별 오류
     */
    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    /**
     * 입력 검증에서 실패한 필드의 경로와 이유를 표현한다. 입력값 자체는 노출하지 않는다.
     *
     * @param field 잘못된 필드 경로
     * @param reason 검증 실패 이유
     */
    public record FieldError(String field, String reason) {
    }
}
