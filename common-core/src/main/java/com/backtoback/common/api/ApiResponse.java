package com.backtoback.common.api;

/**
 * 외부 JSON API의 성공 결과를 공통 형식으로 감싼다.
 * 내부 API의 성공 결과, 204 응답, SSE와 이미지 응답에는 사용하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: 성공 필드와 적용 제외 대상</li>
 * </ul>
 *
 * @param <T> 응답 데이터 타입
 * @param success 처리 성공 여부
 * @param message 사용자 안내 메시지; 조회 성공은 null
 * @param data 처리 결과; API 계약에 따라 null 허용
 */
public record ApiResponse<T>(boolean success, String message, T data) {
    /**
     * 별도 안내 메시지 없이 조회 결과를 감싼다.
     *
     * @param <T> 응답 데이터 타입
     * @param data 조회 결과
     * @return message가 null인 성공 응답
     */
    public static <T> ApiResponse<T> success(T data) {
        return success(null, data);
    }

    /**
     * 작업 결과와 사용자 안내 메시지를 감싼다.
     *
     * @param <T> 응답 데이터 타입
     * @param message 사용자 안내 메시지
     * @param data 작업 결과
     * @return success가 true인 응답
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
