package com.backtoback.contest.global.response;

/**
 * 외부 API의 성공 봉투를 표현한다. 조회는 message를 null로, 작업은 안내 문구로 반환한다.
 * 오류 봉투는 ErrorResponse로 구분해 성공 응답에 불필요한 error 필드를 넣지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §0.3 공통 성공 / 에러 응답: success·message·data 구조.
 *
 * @param <T> API별 데이터 타입
 * @param success 성공 여부
 * @param message 작업 안내 문구 또는 조회 시 null
 * @param data API별 응답 데이터
 */
public record ApiResponse<T>(boolean success, String message, T data) {
    /**
     * 조회 또는 작업 결과를 성공 봉투에 넣는다.
     *
     * @param message 조회 시 null, 작업 시 사용자 안내
     * @param data 응답 데이터
     * @param <T> 응답 데이터 타입
     * @return success가 true인 외부 API 응답
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
